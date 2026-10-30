package tz.co.nlolo.lifeplatform.unitlinked.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * A unit-linked policy's monthly charge dates: the issue date plus whole months -- policy.domain.InstalmentDates'
 * counting for MONTHLY, copied rather than widening policy's api for one loop. So a policy issued on 31 January is
 * charged on 28 February and then 31 March (2026-10-29: it stepped from each charge date, and slid to the 28th for
 * good). No charge falls on the issue day itself, when no units have been bought.
 */
public final class ChargeDates {

    private ChargeDates() {}

    /** Every charge date after {@code after} and on or before {@code upTo}, in order. */
    public static List<LocalDate> between(LocalDate issueDate, LocalDate after, LocalDate upTo) {
        List<LocalDate> dates = new ArrayList<>();
        int k = 1;
        LocalDate next = issueDate.plusMonths(1);
        while (!next.isAfter(upTo)) {
            if (next.isAfter(after)) {
                dates.add(next);
            }
            next = issueDate.plusMonths(++k);
        }
        return dates;
    }
}
