package tz.co.nlolo.lifeplatform.distribution;

import tz.co.nlolo.lifeplatform.distribution.api.LicenseStatus;
import tz.co.nlolo.lifeplatform.distribution.api.TierType;
import tz.co.nlolo.lifeplatform.distribution.domain.AgentProfile;
import tz.co.nlolo.lifeplatform.distribution.domain.CommissionCalculator;
import tz.co.nlolo.lifeplatform.distribution.domain.CommissionCalculator.Accrual;
import tz.co.nlolo.lifeplatform.distribution.domain.CommissionCalculator.AgentWithPlan;
import tz.co.nlolo.lifeplatform.distribution.domain.CommissionPlan;
import tz.co.nlolo.lifeplatform.distribution.domain.CommissionRule;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 4: a plain unit test over {@link CommissionCalculator} -- no Spring, no container, no
 * database. This is a pure function over already-loaded data, so every scenario below is
 * constructed in memory.
 *
 * <p><b>THRESHOLD_BONUS fixture note:</b> {@link CommissionRule}'s constructor (Task 3) rejects
 * {@code TierType.THRESHOLD_BONUS} outright, so no rule carrying that tier can be authored through
 * the public API. To exercise {@code CommissionCalculator}'s own (redundant) THRESHOLD_BONUS skip,
 * {@link #thresholdBonusRule} builds a legal rule and then overwrites its {@code tierType} field
 * via {@link ReflectionTestUtils}, the same "force a value the constructor would reject" pattern
 * already used for persistence-only fields in {@code PolicyApiIntegrationTest}. This is a
 * deliberate workaround for a fixture that cannot otherwise exist, not a claim that such a row is
 * reachable in production.
 *
 * <p><b>Cross-task finding (see task-4-report.md for detail):</b> given that guard, plus the fact
 * that {@code calculate()} never invokes the private {@code applicableAmount(candidate, tier, ...)}
 * with {@code tier == THRESHOLD_BONUS} (only {@code directTier} -- FIRST_YEAR/RENEWAL -- and the
 * two override tiers are ever passed), {@code applicableAmount}'s
 * {@code .filter(r -> r.getTierType() != TierType.THRESHOLD_BONUS)} line is unreachable dead code:
 * the preceding {@code .filter(r -> r.getTierType() == tier)} already excludes a THRESHOLD_BONUS
 * rule for every tier {@code calculate()} ever asks about. {@link #thresholdBonusRuleIsSkippedEvenAsOnlyRule()}
 * still verifies the externally-visible guarantee (an agent whose only rule is THRESHOLD_BONUS
 * accrues nothing), but it cannot exercise that specific filter line in isolation.
 */
class CommissionCalculatorTest {

    private static final String CURRENCY = "TZS";

    // ---- fixtures ---------------------------------------------------------------------------

    private static AgentProfile agentWithStatus(UUID agentId, LicenseStatus status) {
        AgentProfile agent = new AgentProfile(UUID.randomUUID(), UUID.randomUUID(),
            "LIC-" + agentId, LocalDate.now().plusYears(1), null, null, "test-staff");
        agent.setLicenseStatus(status);
        // agentId is @UuidGenerator-assigned only on persist; this is a transient, unpersisted
        // fixture, so the id is forced via reflection to give each agent a stable, distinguishable
        // identity for the accrual assertions below.
        ReflectionTestUtils.setField(agent, "agentId", agentId);
        return agent;
    }

    private static AgentProfile activeAgent(UUID agentId) {
        return agentWithStatus(agentId, LicenseStatus.ACTIVE);
    }

    private static CommissionPlan plan(UUID tenantId) {
        return new CommissionPlan(tenantId, UUID.randomUUID(), "test-staff");
    }

    private static CommissionRule rateRule(UUID tenantId, TierType tier, String rate) {
        return new CommissionRule(tenantId, UUID.randomUUID(), tier, new BigDecimal(rate), null, null, null);
    }

    private static CommissionRule flatRule(UUID tenantId, TierType tier, String flatAmount, String flatCurrency) {
        return new CommissionRule(tenantId, UUID.randomUUID(), tier, null, new BigDecimal(flatAmount), flatCurrency, null);
    }

    /** See class javadoc: forces a tier the constructor would otherwise reject. */
    private static CommissionRule thresholdBonusRule(UUID tenantId) {
        CommissionRule rule = new CommissionRule(tenantId, UUID.randomUUID(), TierType.FIRST_YEAR,
            new BigDecimal("0.05"), null, null, null);
        ReflectionTestUtils.setField(rule, "tierType", TierType.THRESHOLD_BONUS);
        return rule;
    }

    // ---- rate-based FIRST_YEAR ----------------------------------------------------------------

    @Test
    void rateBasedFirstYearAccrualComputesPremiumTimesRateRoundedHalfUp() {
        UUID tenantId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        AgentWithPlan seller = new AgentWithPlan(activeAgent(sellerId), plan(tenantId),
            List.of(rateRule(tenantId, TierType.FIRST_YEAR, "0.075")));

        // 1234.60 * 0.075 = 92.595 -- exactly on the HALF_UP boundary, so this also proves the
        // rounding mode, not just the multiplication.
        List<Accrual> accruals = CommissionCalculator.calculate(
            seller, List.of(), TierType.FIRST_YEAR, new BigDecimal("1234.60"), CURRENCY);

        assertThat(accruals).hasSize(1);
        Accrual accrual = accruals.get(0);
        assertThat(accrual.agentId()).isEqualTo(sellerId);
        assertThat(accrual.tierType()).isEqualTo(TierType.FIRST_YEAR);
        assertThat(accrual.amount()).isEqualByComparingTo("92.60");
        assertThat(accrual.currency()).isEqualTo(CURRENCY);
    }

    // ---- flat-amount FIRST_YEAR ----------------------------------------------------------------

    @Test
    void flatAmountFirstYearAccrualPaysTheFlatAmount() {
        UUID tenantId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        AgentWithPlan seller = new AgentWithPlan(activeAgent(sellerId), plan(tenantId),
            List.of(flatRule(tenantId, TierType.FIRST_YEAR, "500.00", CURRENCY)));

        List<Accrual> accruals = CommissionCalculator.calculate(
            seller, List.of(), TierType.FIRST_YEAR, new BigDecimal("10000.00"), CURRENCY);

        assertThat(accruals).hasSize(1);
        assertThat(accruals.get(0).amount()).isEqualByComparingTo("500.00");
    }

    @Test
    void flatRuleInADifferentCurrencyPaysNothingRatherThanConverting() {
        UUID tenantId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        AgentWithPlan seller = new AgentWithPlan(activeAgent(sellerId), plan(tenantId),
            List.of(flatRule(tenantId, TierType.FIRST_YEAR, "500.00", "USD")));

        List<Accrual> accruals = CommissionCalculator.calculate(
            seller, List.of(), TierType.FIRST_YEAR, new BigDecimal("10000.00"), CURRENCY);

        assertThat(accruals).isEmpty();
    }

    // ---- multi-tier hierarchy -------------------------------------------------------------------

    @Test
    void multiTierPlanProducesThreeAccrualsToSellerParentAndGrandparent() {
        UUID tenantId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        UUID grandparentId = UUID.randomUUID();

        AgentWithPlan seller = new AgentWithPlan(activeAgent(sellerId), plan(tenantId),
            List.of(rateRule(tenantId, TierType.FIRST_YEAR, "0.05")));
        AgentWithPlan parent = new AgentWithPlan(activeAgent(parentId), plan(tenantId),
            List.of(rateRule(tenantId, TierType.OVERRIDE, "0.02")));
        AgentWithPlan grandparent = new AgentWithPlan(activeAgent(grandparentId), plan(tenantId),
            List.of(rateRule(tenantId, TierType.SUPERVISOR_OVERRIDE, "0.01")));

        List<Accrual> accruals = CommissionCalculator.calculate(seller, List.of(parent, grandparent),
            TierType.FIRST_YEAR, new BigDecimal("1000.00"), CURRENCY);

        assertThat(accruals).hasSize(3);

        Accrual firstYear = findByTier(accruals, TierType.FIRST_YEAR);
        assertThat(firstYear.agentId()).isEqualTo(sellerId);
        assertThat(firstYear.amount()).isEqualByComparingTo("50.00");

        Accrual override = findByTier(accruals, TierType.OVERRIDE);
        assertThat(override.agentId()).isEqualTo(parentId);
        assertThat(override.amount()).isEqualByComparingTo("20.00");

        Accrual supervisorOverride = findByTier(accruals, TierType.SUPERVISOR_OVERRIDE);
        assertThat(supervisorOverride.agentId()).isEqualTo(grandparentId);
        assertThat(supervisorOverride.amount()).isEqualByComparingTo("10.00");
    }

    @Test
    void renewalTriggerFiresNoOverrideTiers() {
        UUID tenantId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();

        AgentWithPlan seller = new AgentWithPlan(activeAgent(sellerId), plan(tenantId),
            List.of(rateRule(tenantId, TierType.RENEWAL, "0.03")));
        AgentWithPlan parent = new AgentWithPlan(activeAgent(UUID.randomUUID()), plan(tenantId),
            List.of(rateRule(tenantId, TierType.OVERRIDE, "0.02")));
        AgentWithPlan grandparent = new AgentWithPlan(activeAgent(UUID.randomUUID()), plan(tenantId),
            List.of(rateRule(tenantId, TierType.SUPERVISOR_OVERRIDE, "0.01")));

        List<Accrual> accruals = CommissionCalculator.calculate(seller, List.of(parent, grandparent),
            TierType.RENEWAL, new BigDecimal("1000.00"), CURRENCY);

        assertThat(accruals).hasSize(1);
        assertThat(accruals.get(0).tierType()).isEqualTo(TierType.RENEWAL);
        assertThat(accruals.get(0).agentId()).isEqualTo(sellerId);
    }

    @Test
    void ancestorWithNoPlanAndAncestorLackingTheRelevantTierEachEarnNothingWhileSellerStillAccrues() {
        UUID tenantId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();

        AgentWithPlan seller = new AgentWithPlan(activeAgent(sellerId), plan(tenantId),
            List.of(rateRule(tenantId, TierType.FIRST_YEAR, "0.05")));
        // Parent has no commission plan at all.
        AgentWithPlan parentWithNoPlan = new AgentWithPlan(activeAgent(UUID.randomUUID()), null, null);
        // Grandparent has a plan, but it carries no SUPERVISOR_OVERRIDE rule -- only an unrelated one.
        AgentWithPlan grandparentWithoutRelevantTier = new AgentWithPlan(activeAgent(UUID.randomUUID()),
            plan(tenantId), List.of(rateRule(tenantId, TierType.FIRST_YEAR, "0.05")));

        List<Accrual> accruals = CommissionCalculator.calculate(seller,
            List.of(parentWithNoPlan, grandparentWithoutRelevantTier),
            TierType.FIRST_YEAR, new BigDecimal("1000.00"), CURRENCY);

        assertThat(accruals).hasSize(1);
        assertThat(accruals.get(0).tierType()).isEqualTo(TierType.FIRST_YEAR);
        assertThat(accruals.get(0).agentId()).isEqualTo(sellerId);
    }

    // ---- SUSPENDED / EXPIRED agents ---------------------------------------------------------------

    @Test
    void suspendedSellerAccruesNothingButTheHierarchyIsStillPaid() {
        UUID tenantId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        UUID grandparentId = UUID.randomUUID();

        AgentWithPlan seller = new AgentWithPlan(
            agentWithStatus(UUID.randomUUID(), LicenseStatus.SUSPENDED), plan(tenantId),
            List.of(rateRule(tenantId, TierType.FIRST_YEAR, "0.05")));
        AgentWithPlan parent = new AgentWithPlan(activeAgent(parentId), plan(tenantId),
            List.of(rateRule(tenantId, TierType.OVERRIDE, "0.02")));
        AgentWithPlan grandparent = new AgentWithPlan(activeAgent(grandparentId), plan(tenantId),
            List.of(rateRule(tenantId, TierType.SUPERVISOR_OVERRIDE, "0.01")));

        List<Accrual> accruals = CommissionCalculator.calculate(seller, List.of(parent, grandparent),
            TierType.FIRST_YEAR, new BigDecimal("1000.00"), CURRENCY);

        assertThat(accruals).hasSize(2);
        assertThat(accruals).extracting(Accrual::tierType)
            .containsExactlyInAnyOrder(TierType.OVERRIDE, TierType.SUPERVISOR_OVERRIDE);
        assertThat(findByTier(accruals, TierType.OVERRIDE).agentId()).isEqualTo(parentId);
        assertThat(findByTier(accruals, TierType.SUPERVISOR_OVERRIDE).agentId()).isEqualTo(grandparentId);
    }

    @Test
    void expiredAncestorEarnsNothingWhileSellerAndTheOtherAncestorStillAccrue() {
        UUID tenantId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        UUID grandparentId = UUID.randomUUID();

        AgentWithPlan seller = new AgentWithPlan(activeAgent(sellerId), plan(tenantId),
            List.of(rateRule(tenantId, TierType.FIRST_YEAR, "0.05")));
        AgentWithPlan expiredParent = new AgentWithPlan(
            agentWithStatus(UUID.randomUUID(), LicenseStatus.EXPIRED), plan(tenantId),
            List.of(rateRule(tenantId, TierType.OVERRIDE, "0.02")));
        AgentWithPlan grandparent = new AgentWithPlan(activeAgent(grandparentId), plan(tenantId),
            List.of(rateRule(tenantId, TierType.SUPERVISOR_OVERRIDE, "0.01")));

        List<Accrual> accruals = CommissionCalculator.calculate(seller, List.of(expiredParent, grandparent),
            TierType.FIRST_YEAR, new BigDecimal("1000.00"), CURRENCY);

        assertThat(accruals).hasSize(2);
        assertThat(accruals).extracting(Accrual::tierType)
            .containsExactlyInAnyOrder(TierType.FIRST_YEAR, TierType.SUPERVISOR_OVERRIDE);
        assertThat(findByTier(accruals, TierType.FIRST_YEAR).agentId()).isEqualTo(sellerId);
        assertThat(findByTier(accruals, TierType.SUPERVISOR_OVERRIDE).agentId()).isEqualTo(grandparentId);
    }

    // ---- THRESHOLD_BONUS ---------------------------------------------------------------------

    @Test
    void thresholdBonusRuleIsSkippedEvenAsOnlyRule() {
        UUID tenantId = UUID.randomUUID();
        AgentWithPlan seller = new AgentWithPlan(activeAgent(UUID.randomUUID()), plan(tenantId),
            List.of(thresholdBonusRule(tenantId)));

        List<Accrual> accruals = CommissionCalculator.calculate(
            seller, List.of(), TierType.FIRST_YEAR, new BigDecimal("1000.00"), CURRENCY);

        assertThat(accruals).isEmpty();
    }

    // ---- resolveAncestorIds ------------------------------------------------------------------

    @Test
    void resolveAncestorIdsTerminatesOnATwoNodeCycle() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Map<UUID, UUID> parents = Map.of(a, b, b, a); // A -> B -> A -> ...
        Function<UUID, UUID> parentOf = parents::get;

        List<UUID> chain = CommissionCalculator.resolveAncestorIds(a, parentOf);

        // Must terminate (this call returning at all is the primary assertion) and never exceed
        // the walk cap (2, mirrored here since CommissionCalculator.MAX_HIERARCHY_WALK_DEPTH is
        // package-private to distribution.domain and this test lives in distribution).
        assertThat(chain).containsExactly(b);
        assertThat(chain.size()).isLessThanOrEqualTo(2);
    }

    @Test
    void resolveAncestorIdsCapsAFiveDeepChainAtTheWalkLimit() {
        UUID seller = UUID.randomUUID();
        UUID parent = UUID.randomUUID();
        UUID grandparent = UUID.randomUUID();
        UUID greatGrandparent = UUID.randomUUID();
        UUID greatGreatGrandparent = UUID.randomUUID();
        UUID apex = UUID.randomUUID();

        Map<UUID, UUID> parents = Map.of(
            seller, parent,
            parent, grandparent,
            grandparent, greatGrandparent,
            greatGrandparent, greatGreatGrandparent,
            greatGreatGrandparent, apex);
        Function<UUID, UUID> parentOf = parents::get;

        List<UUID> chain = CommissionCalculator.resolveAncestorIds(seller, parentOf);

        assertThat(chain).containsExactly(parent, grandparent);
        assertThat(chain).hasSize(2);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static Accrual findByTier(List<Accrual> accruals, TierType tier) {
        return accruals.stream().filter(a -> a.tierType() == tier).findFirst()
            .orElseThrow(() -> new AssertionError("no accrual of tier " + tier));
    }
}
