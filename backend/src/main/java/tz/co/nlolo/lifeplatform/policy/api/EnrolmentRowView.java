package tz.co.nlolo.lifeplatform.policy.api;

import java.math.BigDecimal;
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
                                String currency) {}
