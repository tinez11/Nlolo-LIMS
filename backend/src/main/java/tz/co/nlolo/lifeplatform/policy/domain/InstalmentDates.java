package tz.co.nlolo.lifeplatform.policy.domain;

import java.time.LocalDate;
import java.time.Period;

/**
 * Where a policy's instalments fall: the dates billing raises its invoices on (plan R4). "The next premium
 * date" -- when an added life's cover starts, a removal takes effect, a restated premium is first due -- is
 * the first one strictly after a given day.
 *
 * <p>Stepped exactly as billing steps: one period from the issue date, then one period from each due date
 * in turn ({@code BillingApiImpl.nextPeriodStart} over a cursor). So a policy issued on 31 January falls due
 * on 28 February and then 28 March -- not 31 March -- and these dates must agree with that, or an added
 * life's cover would start on a day no invoice falls on.
 */
public final class InstalmentDates {

    private InstalmentDates() {}

    /** The first instalment date strictly after {@code day}, counted from {@code issueDate}. */
    public static LocalDate nextAfter(LocalDate issueDate, String frequency, LocalDate day) {
        Period step = step(frequency);
        LocalDate next = issueDate.plus(step);
        while (!next.isAfter(day)) {
            next = next.plus(step);
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
