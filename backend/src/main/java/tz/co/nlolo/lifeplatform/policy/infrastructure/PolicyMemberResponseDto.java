package tz.co.nlolo.lifeplatform.policy.infrastructure;

import org.springframework.data.domain.Page;
import tz.co.nlolo.lifeplatform.policy.api.ExitReason;
import tz.co.nlolo.lifeplatform.policy.api.MemberStatus;
import tz.co.nlolo.lifeplatform.policy.api.MemberType;
import tz.co.nlolo.lifeplatform.policy.api.MemberUnderwritingStatus;
import tz.co.nlolo.lifeplatform.policy.api.PolicyMemberView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One insured life on a scheme.
 *
 * <p>Every amount is a {@link MoneyDto} -- a decimal string plus a currency code, never a
 * JSON number. Money on this platform is always spelled that way, because a JSON number
 * is a double by the time it reaches a browser and a decimal that survives Postgres and
 * Java exactly should not lose that on the last hop. The scheme's currency rides along on
 * each figure rather than being looked up from the scheme by every caller.
 *
 * <p>{@code benefit} and {@code covered} both appear, and they differ whenever the free
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
                                       MoneyDto salary, MoneyDto benefit, MoneyDto covered,
                                       LocalDate benefitEffectiveFrom,
                                       /**
                                        * The six below were on {@link PolicyMemberView} since plan 1
                                        * and were never mapped here, so no HTTP client could read
                                        * them. That was not cosmetic: the console's member roll
                                        * reaches a name through {@code memberPartyId}, a FREEFORM
                                        * credit-life borrower has no party row, and so a roll whose
                                        * whole purpose is answering "is this person covered" showed
                                        * an em dash for every borrower on it.
                                        */
                                       MemberType memberType, String memberName,
                                       String memberReference, String loanAccountNumber,
                                       ExitReason exitReason, MoneyDto outstandingBalanceAtExit) {

    public static PolicyMemberResponseDto from(PolicyMemberView view) {
        return new PolicyMemberResponseDto(view.policyMemberId(), view.memberPartyId(), view.gradeCode(),
            view.joinedOn(), view.leftOn(), view.status(), view.underwritingStatus(),
            view.underwritingCaseId(),
            money(view.salaryAmount(), view.currency()),
            money(view.benefitAmount(), view.currency()),
            money(view.coveredAmount(), view.currency()),
            view.benefitEffectiveFrom(),
            view.memberType(), view.memberName(), view.memberReference(),
            view.loanAccountNumber(), view.exitReason(),
            money(view.outstandingBalanceAtExit(), view.currency()));
    }

    private static MoneyDto money(BigDecimal amount, String currency) {
        return amount != null ? new MoneyDto(amount.toPlainString(), currency) : null;
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
