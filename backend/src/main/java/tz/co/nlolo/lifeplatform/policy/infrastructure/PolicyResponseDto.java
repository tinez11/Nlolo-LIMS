package tz.co.nlolo.lifeplatform.policy.infrastructure;

import tz.co.nlolo.lifeplatform.policy.api.BeneficiaryView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyStatus;
import tz.co.nlolo.lifeplatform.policy.api.PolicyView;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** See this task's "Important wire-shape note" above. */
public record PolicyResponseDto(String policyNumber, UUID policyholderPartyId, UUID productId, UUID productVersionId,
                                 UUID agentOfRecordId, PolicyStatus status, LocalDate issueDate,
                                 MoneyDto sumAssured, MoneyDto cashValue, MoneyDto premium, String premiumFrequency,
                                 List<BeneficiaryView> beneficiaries,
                                 // The policy term (V6). Null together on a product that does not
                                 // term, and on every policy issued before the migration.
                                 LocalDate commencementDate, Integer policyTermMonths,
                                 Integer premiumPayingTermMonths, LocalDate maturityDate,
                                 UUID lifeAssuredPartyId,
                                 /** GROUP_LIFE means the lives are a member schedule, not this record. */
                                 String productCategory) {

    public static PolicyResponseDto from(PolicyView view) {
        return new PolicyResponseDto(view.policyNumber(), view.policyholderPartyId(), view.productId(), view.productVersionId(),
            view.agentOfRecordId(), view.status(), view.issueDate(),
            new MoneyDto(view.sumAssuredAmount().toPlainString(), view.sumAssuredCurrency()),
            new MoneyDto(view.cashValueAmount().toPlainString(), view.cashValueCurrency()),
            new MoneyDto(view.premiumAmount().toPlainString(), view.premiumCurrency()), view.premiumFrequency(),
            view.beneficiaries(),
            view.commencementDate(), view.policyTermMonths(),
            view.premiumPayingTermMonths(), view.maturityDate(),
            view.lifeAssuredPartyId(), view.productCategory());
    }
}
