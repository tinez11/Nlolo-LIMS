package tz.co.nlolo.lifeplatform.policy.infrastructure;

import tz.co.nlolo.lifeplatform.policy.api.EnrolmentRejection;
import tz.co.nlolo.lifeplatform.policy.api.EnrolmentRowView;
import tz.co.nlolo.lifeplatform.policy.api.RowOutcome;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * One row of a lender's schedule, as a browser reads it.
 *
 * <p>Added with {@link ExitRowResponseDto} and for the same reason: this endpoint returned the
 * domain view directly, so {@code premiumAmount} reached the wire as a bare JSON number with no
 * currency. It had not yet been noticed only because no screen rendered it — the exits side was
 * bitten first, and identically.
 *
 * <p><b>{@code memberReference} is the other half, and it was invisible rather than wrong.</b>
 * {@code EnrolmentRowView} has carried it since enrolment was built, but the OpenAPI schema never
 * declared it, so the generated client had no such field and the console could not read one. The
 * rows table says on screen that "member references are in the report, not here" — which was true
 * of the contract and false of the data, and is the sort of statement that quietly outlives the
 * limitation it describes.
 */
public record EnrolmentRowResponseDto(int lineNumber, String loanAccountNumber,
                                       String borrowerFullName, RowOutcome outcome,
                                       EnrolmentRejection reasonCode, String reason,
                                       UUID policyMemberId, String memberReference,
                                       MoneyDto premiumAmount) {

    public static EnrolmentRowResponseDto from(EnrolmentRowView view) {
        return new EnrolmentRowResponseDto(view.lineNumber(), view.loanAccountNumber(),
            view.borrowerFullName(), view.outcome(), view.reasonCode(), view.reason(),
            view.policyMemberId(), view.memberReference(),
            money(view.premiumAmount(), view.currency()));
    }

    public static List<EnrolmentRowResponseDto> from(List<EnrolmentRowView> views) {
        return views.stream().map(EnrolmentRowResponseDto::from).toList();
    }

    /** Null on a refused row: nothing is charged for a borrower who was not enrolled. */
    private static MoneyDto money(BigDecimal amount, String currency) {
        return amount != null ? new MoneyDto(amount.toPlainString(), currency) : null;
    }
}
