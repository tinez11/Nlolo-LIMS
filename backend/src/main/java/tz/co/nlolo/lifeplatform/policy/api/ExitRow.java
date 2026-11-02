package tz.co.nlolo.lifeplatform.policy.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One readable line of a lender's exits file.
 *
 * @param lineNumber the line in the LENDER's own file, so the report reads beside the
 *     spreadsheet that produced it. Line 1 is the header; data starts at 2.
 * @param memberReference the reference the insurer issued and the enrolment report gave back, or null where the
 *     row names the loan by its account number instead
 * @param loanAccountNumber the lender's own number for the loan (V44), the one thing the lender knows it by; null
 *     where the row gave the member reference alone. One of the two is always present.
 * @param outstandingBalanceAtExit the lender's own figure, or null where they do not track one.
 *     Recorded, never trusted — our declining schedule is what values a claim.
 */
public record ExitRow(int lineNumber,
                       String memberReference,
                       String loanAccountNumber,
                       LocalDate exitDate,
                       ExitReason exitReason,
                       BigDecimal outstandingBalanceAtExit) {}
