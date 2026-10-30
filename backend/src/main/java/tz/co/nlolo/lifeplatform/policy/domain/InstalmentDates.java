package tz.co.nlolo.lifeplatform.policy.domain;

import java.time.LocalDate;
import java.time.Period;

/**
 * Where a policy's instalments fall: the dates billing raises its invoices on (plan R4). "The next premium
 * date" -- when an added life's cover starts, a removal takes effect, a restated premium is first due -- is
 * the first one strictly after a given day.
 *
 * <p>Counted as billing counts them (billing V12, 2026-10-29): the issue date plus whole periods, so a policy issued on
 * 31 January falls due on 28 February and then 31 March. Both used to step from the previous date -- 28 February, then
 * 28 March for good -- and these dates must agree with billing's, or an added life's cover would start on a day no
 * invoice falls on.
 */
public final class InstalmentDates {

    private InstalmentDates() {}

    /** The first instalment date strictly after {@code day}, counted from {@code issueDate}. */
    public static LocalDate nextAfter(LocalDate issueDate, String frequency, LocalDate day) {
        int months = (int) step(frequency).toTotalMonths();
        int k = 1;
        LocalDate next = issueDate.plusMonths(months);
        while (!next.isAfter(day)) {
            next = issueDate.plusMonths((long) ++k * months);
        }
        return next;
    }

    private static Period step(String frequency) {
        return switch (frequency) {
            case "MONTHLY" -> Period.ofMonths(1);
            case "QUARTERLY" -> Period.ofMonths(3);
            case "ANNUALLY" -> Period.ofYears(1);
            default -> throw new IllegalArgumentException("A " + frequency + " premium has no instalment dates");
        };
    }
}
