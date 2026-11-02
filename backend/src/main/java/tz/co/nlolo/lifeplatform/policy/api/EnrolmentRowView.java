package tz.co.nlolo.lifeplatform.policy.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One line of a lender's file and what happened to it.
 *
 * @param lineNumber the line in the LENDER's own file, so the report reads beside the
 *     spreadsheet that produced it.
 * @param reasonCode null on anything that is not a rejection. A capped row says what it
 *     is through {@code outcome}; the codes name refusals only.
 * @param reason prose for a human. On a rejection it ends with the sentence that matters
 *     more than anything else in this feature.
 * @param policyMemberId the member this row became, written at acceptance. Null while the
 *     submission is pending, and null forever on a rejected row.
 * @param premiumAmount what THIS borrower was charged, written at acceptance beside
 *     {@code policyMemberId} and null in the same two cases. Per row rather than only as a
 *     file total, because a refund follows the money that was actually taken for this loan.
 * @param loanAmount, loanStartDate, loanTermMonths the loan as the lender's file stated it (2026-11-02): what a
 *     person reading the file needs to see of each borrower. Null where the row could not be read.
 */
public record EnrolmentRowView(int lineNumber,
                                String loanAccountNumber,
                                String borrowerFullName,
                                RowOutcome outcome,
                                EnrolmentRejection reasonCode,
                                String reason,
                                UUID policyMemberId,
                                String memberReference,
                                BigDecimal premiumAmount,
                                /** The scheme's currency, so the premium can reach the wire as money. */
                                String currency,
                                BigDecimal loanAmount,
                                LocalDate loanStartDate,
                                Integer loanTermMonths) {

    /** A row without its loan, for callers that only render outcomes (the lender's report). */
    public EnrolmentRowView(int lineNumber, String loanAccountNumber, String borrowerFullName, RowOutcome outcome,
                            EnrolmentRejection reasonCode, String reason, UUID policyMemberId, String memberReference,
                            BigDecimal premiumAmount, String currency) {
        this(lineNumber, loanAccountNumber, borrowerFullName, outcome, reasonCode, reason, policyMemberId,
            memberReference, premiumAmount, currency, null, null, null);
    }
}
