package tz.co.nlolo.lifeplatform.policy.infrastructure;

import org.springframework.data.domain.Page;
import tz.co.nlolo.lifeplatform.policy.api.MemberStatus;
import tz.co.nlolo.lifeplatform.policy.api.MemberUnderwritingStatus;
import tz.co.nlolo.lifeplatform.policy.api.PolicyMemberView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One insured life on a scheme.
 *
 * <p>{@code benefit} and {@code covered} are both sent, and they differ whenever the free
 * cover limit bites. The console shows {@code covered} as the member's cover, because that
 * is the figure a claim pays; {@code benefit} is shown beside it only when it is larger,
 * so the gap is visible rather than implied.
 *
 * <p>All three money fields are null for a member whose cover has not started as at today
 * -- rendered as "not yet in force" and never as a zero, which would read as insured for
 * nothing.
 */
public record PolicyMemberResponseDto(UUID policyMemberId, UUID memberPartyId, String gradeCode,
                                       LocalDate joinedOn, LocalDate leftOn, MemberStatus status,
                                       MemberUnderwritingStatus underwritingStatus, UUID underwritingCaseId,
                                       BigDecimal salaryAmount, BigDecimal benefitAmount,
                                       BigDecimal coveredAmount, LocalDate benefitEffectiveFrom) {

    public static PolicyMemberResponseDto from(PolicyMemberView view) {
        return new PolicyMemberResponseDto(view.policyMemberId(), view.memberPartyId(), view.gradeCode(),
            view.joinedOn(), view.leftOn(), view.status(), view.underwritingStatus(),
            view.underwritingCaseId(), view.salaryAmount(), view.benefitAmount(),
            view.coveredAmount(), view.benefitEffectiveFrom());
    }

    /** Same envelope shape as {@link PolicySearchResponse}, so the console pages both alike. */
    public record PageResponse(List<PolicyMemberResponseDto> items, PolicySearchResponse.PageMetaDto page) {
        public static PageResponse from(Page<PolicyMemberView> springPage) {
            return new PageResponse(
                springPage.getContent().stream().map(PolicyMemberResponseDto::from).toList(),
                new PolicySearchResponse.PageMetaDto(springPage.getNumber(), springPage.getSize(),
                    (int) springPage.getTotalElements()));
        }
    }
}
