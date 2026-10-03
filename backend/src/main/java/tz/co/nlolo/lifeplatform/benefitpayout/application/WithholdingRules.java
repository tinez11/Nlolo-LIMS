package tz.co.nlolo.lifeplatform.benefitpayout.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.benefitpayout.api.PayoutStateException;
import tz.co.nlolo.lifeplatform.benefitpayout.domain.PayoutInstalment;
import tz.co.nlolo.lifeplatform.benefitpayout.domain.WithholdingRule;
import tz.co.nlolo.lifeplatform.benefitpayout.infrastructure.WithholdingRuleRepository;
import tz.co.nlolo.lifeplatform.product.api.PayoutKind;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Tax-withholding rules (product step 5, spec Q8): proposed by one finance user, approved by
 * another, and applied to an instalment at the moment its gross becomes final -- approval.
 *
 * <p>The rules are data, never code: the rate, the payout kinds and the legal reference are what
 * finance enters. With no approved rule in force for a payout's kind and due date, nothing is
 * withheld, and the instalment still records that the rules were checked.
 */
@Service
public class WithholdingRules {

    private static final Set<String> KINDS = java.util.Arrays.stream(PayoutKind.values()).map(Enum::name)
        .collect(Collectors.toUnmodifiableSet());

    private final WithholdingRuleRepository rules;

    public WithholdingRules(WithholdingRuleRepository rules) {
        this.rules = rules;
    }

    /**
     * Once per Idempotency-Key: a retried proposal returns the rule it created; the same key for a
     * different rule is refused, as every other keyed create on the platform refuses it.
     */
    @Transactional
    public WithholdingRule propose(List<String> kinds, BigDecimal ratePercent, LocalDate from, LocalDate to,
                                   String legalReference, String proposedBy, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new PayoutStateException("An Idempotency-Key header is required to propose a withholding rule");
        }
        Optional<WithholdingRule> earlier = rules.findByTenantIdAndIdempotencyKey(TenantContext.get(), idempotencyKey);
        if (earlier.isPresent()) {
            WithholdingRule r = earlier.get();
            boolean same = r.getPayoutKinds().equals(kinds) && r.getRatePercent().compareTo(ratePercent) == 0
                && r.getEffectiveFrom().equals(from) && java.util.Objects.equals(r.getEffectiveTo(), to)
                && r.getLegalReference().equals(legalReference);
            if (!same) {
                throw new PayoutStateException("This Idempotency-Key was already used for a different request. "
                    + "Send a new key for a new withholding rule.");
            }
            return r;
        }
        if (kinds == null || kinds.isEmpty() || !KINDS.containsAll(kinds)) {
            throw new PayoutStateException("A withholding rule names the payout kinds it applies to, from " + KINDS);
        }
        if (ratePercent == null || ratePercent.signum() <= 0 || ratePercent.compareTo(new BigDecimal("100")) >= 0) {
            throw new PayoutStateException("A withholding rate must be greater than 0% and less than 100%");
        }
        if (from == null) {
            throw new PayoutStateException("A withholding rule needs the date it takes effect");
        }
        if (to != null && to.isBefore(from)) {
            throw new PayoutStateException("A withholding rule cannot end before it begins");
        }
        if (legalReference == null || legalReference.isBlank()) {
            throw new PayoutStateException("A withholding rule names the law or ruling it applies");
        }
        return rules.saveAndFlush(new WithholdingRule(TenantContext.get(), kinds, ratePercent, from, to, legalReference.trim(),
            proposedBy, idempotencyKey));
    }

    @Transactional
    public WithholdingRule approve(UUID ruleId, String approver) {
        WithholdingRule rule = load(ruleId);
        for (WithholdingRule other : rules.findByTenantIdAndStatus(TenantContext.get(), WithholdingRule.Status.APPROVED.name())) {
            if (rule.overlaps(other)) {
                throw new PayoutStateException("An approved withholding rule already applies to "
                    + String.join(", ", other.getPayoutKinds()) + " from " + other.getEffectiveFrom()
                    + (other.getEffectiveTo() != null ? " to " + other.getEffectiveTo() : "")
                    + " -- give that one an end date first");
            }
        }
        rule.approve(approver);
        return rules.save(rule);
    }

    @Transactional
    public WithholdingRule withdraw(UUID ruleId, String withdrawnBy) {
        WithholdingRule rule = load(ruleId);
        rule.withdraw(withdrawnBy);
        return rules.save(rule);
    }

    @Transactional(readOnly = true)
    public List<WithholdingRule> list() {
        return rules.findByTenantIdOrderByEffectiveFromDescProposedAtDesc(TenantContext.get());
    }

    /** Applied at approval: the one approved rule in force for the kind on the due date, or none. */
    void apply(PayoutInstalment i) {
        String kind = i.kind().name();
        Optional<WithholdingRule> rule = rules.findByTenantIdAndStatus(i.getTenantId(), WithholdingRule.Status.APPROVED.name()).stream()
            .filter(r -> r.appliesTo(kind, i.getDueDate())).findFirst();
        i.applyWithholding(rule.map(WithholdingRule::getRatePercent).orElse(BigDecimal.ZERO),
            rule.map(WithholdingRule::getRuleId).orElse(null));
    }

    private WithholdingRule load(UUID ruleId) {
        return rules.findByRuleIdAndTenantId(ruleId, TenantContext.get()).orElseThrow(() -> new PayoutStateException("No withholding rule " + ruleId));
    }
}
