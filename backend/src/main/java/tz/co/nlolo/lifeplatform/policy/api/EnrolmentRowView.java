package tz.co.nlolo.lifeplatform.policy.api;

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
 */
public record EnrolmentRowView(int lineNumber,
                                String loanAccountNumber,
                                String borrowerFullName,
                                RowOutcome outcome,
                                EnrolmentRejection reasonCode,
                                String reason,
                                UUID policyMemberId) {}
