package tz.co.nlolo.lifeplatform.policy.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One line of a lender's exits file and what happened to it.
 *
 * @param reason prose for a human. On a rejection it says plainly that the loan is STILL ON
 *     COVER -- the opposite of the enrolment report's sentence, and needed for the same
 *     reason: a lender who thinks a loan came off cover stops expecting to be charged for it.
 * @param policyMemberId the member this row took off cover, written at acceptance. Null while
 *     the file is pending, and null forever on a rejected row.
 */
public record ExitRowView(int lineNumber,
                           String memberReference,
                           LocalDate exitDate,
                           ExitReason exitReason,
                           BigDecimal outstandingBalanceAtExit,
                           String outcome,
                           ExitRejection reasonCode,
                           String reason,
                           UUID policyMemberId) {}
