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
                                      BenefitBasis benefitBasis, MoneyDto flatBenefit,
                                      // A ratio, not money -- 3.5x has no currency. Same
                                      // reading that keeps sharePercent a plain number.
                                      BigDecimal salaryMultiple, MoneyDto fcl,
                                      long activeMemberCount, MoneyDto totalCovered,
                                      long membersRequiringEvidence,
                                      List<GroupSchemeGradeDto> grades) {

    public static GroupSchemeResponseDto from(GroupSchemeView view) {
        String currency = view.currency();
        return new GroupSchemeResponseDto(view.policyNumber(), view.policyholderPartyId(), view.status(),
            view.commencementDate(), view.policyTermMonths(), view.benefitBasis(),
            money(view.flatBenefitAmount(), currency), view.salaryMultiple(),
            money(view.fclAmount(), currency),
            view.activeMemberCount(),
            money(view.totalCoveredAmount(), currency),
            view.membersRequiringEvidence(),
            view.grades().stream().map(g -> GroupSchemeGradeDto.from(g, currency)).toList());
    }

    private static MoneyDto money(BigDecimal amount, String currency) {
        return amount != null ? new MoneyDto(amount.toPlainString(), currency) : null;
    }
}
