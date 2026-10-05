package tz.co.nlolo.lifeplatform.unitlinked.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * A unit-linked policy's monthly charge dates: one month from the issue date, then one month from each charge date
 * in turn -- policy.domain.InstalmentDates' stepping for MONTHLY, copied rather than widening policy's api for one
 * loop. So a policy issued on 31 January is charged on 28 February and then 28 March. No charge falls on the issue
 * day itself, when no units have been bought.
 */
public final class ChargeDates {

    private ChargeDates() {}

    /** Every charge date after {@code after} and on or before {@code upTo}, in order. */
    public static List<LocalDate> between(LocalDate issueDate, LocalDate after, LocalDate upTo) {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate next = issueDate.plusMonths(1);
        while (!next.isAfter(upTo)) {
            if (next.isAfter(after)) {
                dates.add(next);
            }
            next = next.plusMonths(1);
        }
        return dates;
    }
}
