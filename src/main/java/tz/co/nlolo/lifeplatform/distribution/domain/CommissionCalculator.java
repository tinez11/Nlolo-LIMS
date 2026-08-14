package tz.co.nlolo.lifeplatform.distribution.domain;

import tz.co.nlolo.lifeplatform.distribution.api.TierType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * THE ENTIRE ALGORITHM BELOW IS AN INVENTED PLACEHOLDER, flagged rather than guessed silently --
 * the same treatment M2 gave the underwriting decision engine and M4 gave premium rates.
 *
 * <p>Di1 (docs/03-aggregate-design.md:148-154) specifies CommissionPlan/CommissionRule's SHAPE
 * (five tier types, getApplicablePlan(), "walks the plan's rules against actual production") and
 * NO calculation semantics at all. No Phase 0 document defines tier precedence, what "actual
 * production" measures, how far a hierarchy override walks, or what happens on lapse -- the word
 * "clawback" appears in no doc and no file on this platform. Every rule encoded here is this
 * plan's own decision and needs Actuarial/Distribution sign-off:
 *
 * <ul>
 *   <li><b>"Actual production" = PREMIUM, not sum assured.</b> It is the only money figure both
 *       policy.PolicyIssued and a premium collection carry, and commission-on-premium is the
 *       ordinary life convention.</li>
 *   <li><b>Tier selection is by TRIGGER, not precedence.</b> Issuance fires FIRST_YEAR; a
 *       non-first premium collection fires RENEWAL. Exactly one direct-agent rule per event, no
 *       stacking. A first-invoice collection fires nothing, or it would double-pay against
 *       FIRST_YEAR.</li>
 *   <li><b>Hierarchy walks at most 2 levels, depth-capped and cycle-safe.</b> OVERRIDE credits
 *       the seller's parent, SUPERVISOR_OVERRIDE the parent's parent.
 *       agent_profile.hierarchy_parent_id is a self-FK with NO cycle constraint in the DDL, so a
 *       cycle in the data must terminate rather than hang or accrue forever.</li>
 *   <li><b>Each ancestor is paid from ITS OWN plan.</b> An ancestor with no plan, or no rule of
 *       the relevant tier, earns nothing -- silently and correctly.</li>
 *   <li><b>THRESHOLD_BONUS is not computed</b> (M7 user decision 1): commission_rule
 *       .threshold_condition is an untyped JSONB whose schema is defined nowhere, so evaluating it
 *       would mean inventing a format with no business input. Rules of that tier are skipped here
 *       and rejected at authoring time.</li>
 * </ul>
 */
public final class CommissionCalculator {

    /** Hard cap on the hierarchy walk. Two levels is all the tier vocabulary needs (OVERRIDE,
     * SUPERVISOR_OVERRIDE), and a cap is mandatory because the DDL permits a cycle. */
    static final int MAX_HIERARCHY_WALK_DEPTH = 2;

    private CommissionCalculator() {}

    /** One accrual the caller should persist. */
    public record Accrual(UUID agentId, TierType tierType, BigDecimal amount, String currency) {}

    /** An agent plus the plan and rules that apply to it, already loaded by the caller. */
    public record AgentWithPlan(AgentProfile agent, CommissionPlan plan, List<CommissionRule> rules) {}

    /**
     * @param seller        the agent of record, already loaded
     * @param ancestors     the seller's hierarchy chain, nearest first, already walked and
     *                      cycle-checked by the caller (see resolveAncestors)
     * @param directTier    FIRST_YEAR for an issuance, RENEWAL for a non-first premium collection
     * @param premium       the premium amount the event carried
     * @param currency      that premium's currency
     */
    public static List<Accrual> calculate(AgentWithPlan seller, List<AgentWithPlan> ancestors,
                                           TierType directTier, BigDecimal premium, String currency) {
        List<Accrual> accruals = new ArrayList<>();
        applicableAmount(seller, directTier, premium, currency)
            .ifPresent(amount -> accruals.add(new Accrual(seller.agent().getAgentId(), directTier, amount, currency)));

        // Override tiers apply only to an issuance-driven accrual. A renewal does not re-pay the
        // hierarchy -- an invented rule, and one of the most likely to be corrected.
        if (directTier == TierType.FIRST_YEAR) {
            TierType[] overrideTiers = { TierType.OVERRIDE, TierType.SUPERVISOR_OVERRIDE };
            for (int level = 0; level < ancestors.size() && level < MAX_HIERARCHY_WALK_DEPTH; level++) {
                AgentWithPlan ancestor = ancestors.get(level);
                TierType tier = overrideTiers[level];
                applicableAmount(ancestor, tier, premium, currency)
                    .ifPresent(amount -> accruals.add(
                        new Accrual(ancestor.agent().getAgentId(), tier, amount, currency)));
            }
        }
        return accruals;
    }

    /** Empty when the agent cannot accrue, has no plan, or the plan has no rule of this tier --
     * all three are normal, not errors. */
    private static Optional<BigDecimal> applicableAmount(AgentWithPlan candidate, TierType tier,
                                                          BigDecimal premium, String currency) {
        if (candidate == null || candidate.agent() == null || !candidate.agent().canAccrueCommission()) {
            return Optional.empty();
        }
        if (candidate.plan() == null || candidate.rules() == null) {
            return Optional.empty();
        }
        return candidate.rules().stream()
            .filter(r -> r.getTierType() == tier)
            .filter(r -> r.getTierType() != TierType.THRESHOLD_BONUS) // never computed -- see class javadoc
            .findFirst()
            .flatMap(rule -> amountFor(rule, premium, currency));
    }

    /** A rule is rate-based or flat, never both and never neither (V2's XOR CHECK). A flat rule in
     * a different currency is skipped rather than converted -- no FX table exists anywhere on this
     * platform, and inventing a rate would be worse than not paying. */
    private static Optional<BigDecimal> amountFor(CommissionRule rule, BigDecimal premium, String currency) {
        if (rule.getRate() != null) {
            return Optional.of(premium.multiply(rule.getRate())
                .setScale(2, java.math.RoundingMode.HALF_UP));
        }
        if (rule.getFlatAmount() != null && currency.equals(rule.getFlatCurrency())) {
            return Optional.of(rule.getFlatAmount());
        }
        return Optional.empty();
    }

    /** Walks hierarchy_parent_id, nearest ancestor first, stopping at MAX_HIERARCHY_WALK_DEPTH or
     * on a repeated agentId. The cycle check is not defensive padding: the DDL's self-FK has no
     * constraint preventing A -> B -> A, and without this the walk would not terminate. */
    public static List<UUID> resolveAncestorIds(UUID sellerId, java.util.function.Function<UUID, UUID> parentOf) {
        List<UUID> chain = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        seen.add(sellerId);
        UUID current = parentOf.apply(sellerId);
        while (current != null && chain.size() < MAX_HIERARCHY_WALK_DEPTH && seen.add(current)) {
            chain.add(current);
            current = parentOf.apply(current);
        }
        return chain;
    }
}
