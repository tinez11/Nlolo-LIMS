package tz.co.nlolo.lifeplatform.policy.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One readable line of a lender's exits file.
 *
 * @param lineNumber the line in the LENDER's own file, so the report reads beside the
 *     spreadsheet that produced it. Line 1 is the header; data starts at 2.
 * @param memberReference the reference the insurer issued and the enrolment report gave back.
 *     Unlike an enrolment row there is no blank case: an exits file is by definition about a
 *     loan we already cover.
 * @param outstandingBalanceAtExit the lender's own figure, or null where they do not track one.
 *     Recorded, never trusted — our declining schedule is what values a claim.
 */
public record ExitRow(int lineNumber,
                       String memberReference,
                       LocalDate exitDate,
                       ExitReason exitReason,
                       BigDecimal outstandingBalanceAtExit) {}
