package tz.co.nlolo.lifeplatform.policy.infrastructure;

import tz.co.nlolo.lifeplatform.policy.api.BenefitBasis;
import tz.co.nlolo.lifeplatform.policy.api.GroupSchemeView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A scheme as the console reads it.
 *
 * @param totalCovered what every active member is actually covered for -- derived from
 *     the schedule on every read, and equal to the master policy's sum assured.
 * @param fcl null for a scheme with no free cover limit. Rendered as "no limit", never as
 *     zero: the two mean opposite things.
 */
public record GroupSchemeResponseDto(String policyNumber, UUID policyholderPartyId, PolicyStatus status,
                                      LocalDate commencementDate, Integer policyTermMonths,
                                      BenefitBasis benefitBasis, BigDecimal flatBenefitAmount,
                                      BigDecimal salaryMultiple, MoneyDto fcl,
                                      long activeMemberCount, MoneyDto totalCovered,
                                      long membersRequiringEvidence,
                                      List<GroupSchemeGradeDto> grades) {

    public static GroupSchemeResponseDto from(GroupSchemeView view) {
        return new GroupSchemeResponseDto(view.policyNumber(), view.policyholderPartyId(), view.status(),
            view.commencementDate(), view.policyTermMonths(), view.benefitBasis(),
            view.flatBenefitAmount(), view.salaryMultiple(),
            view.fclAmount() != null ? new MoneyDto(view.fclAmount().toPlainString(), view.currency()) : null,
            view.activeMemberCount(),
            new MoneyDto(view.totalCoveredAmount().toPlainString(), view.currency()),
            view.membersRequiringEvidence(),
            view.grades().stream().map(GroupSchemeGradeDto::from).toList());
    }
}
