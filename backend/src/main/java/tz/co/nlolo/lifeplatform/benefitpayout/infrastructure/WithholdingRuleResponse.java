package tz.co.nlolo.lifeplatform.benefitpayout.infrastructure;

import tz.co.nlolo.lifeplatform.benefitpayout.domain.WithholdingRule;

import java.util.List;

/** A withholding rule on the wire (product step 5). The rate is a decimal string without trailing zeros. */
public record WithholdingRuleResponse(String ruleId, List<String> payoutKinds, String ratePercent, String effectiveFrom,
                                      String effectiveTo, String legalReference, String status, String proposedBy,
                                      String proposedAt, String approvedBy, String approvedAt,
                                      String endedBy, String endedAt) {

    static WithholdingRuleResponse from(WithholdingRule r) {
        return new WithholdingRuleResponse(r.getRuleId().toString(), r.getPayoutKinds(),
            r.getRatePercent().stripTrailingZeros().toPlainString(), r.getEffectiveFrom().toString(),
            r.getEffectiveTo() != null ? r.getEffectiveTo().toString() : null, r.getLegalReference(), r.status().name(),
            r.getProposedBy(), r.getProposedAt().toString(), r.getApprovedBy(),
            r.getApprovedAt() != null ? r.getApprovedAt().toString() : null,
            r.getEndedBy(), r.getEndedAt() != null ? r.getEndedAt().toString() : null);
    }
}
