package tz.co.nlolo.lifeplatform.reporting.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/** Persistency at month N is measured at the START of month N: activation date plus N-1 months. A month is
 * reported only once every policy in the cohort has reached it, so a half-measured column never appears. */
public final class PersistencyMonths {
    private PersistencyMonths() {}

    public static final List<Integer> MONTHS = List.of(13, 25, 37, 61);

    public static LocalDate measuredOn(LocalDate activatedOn, int month) {
        return activatedOn.plusMonths(month - 1L);
    }

    public static boolean reached(YearMonth cohortTo, int month, LocalDate today) {
        return !measuredOn(cohortTo.atEndOfMonth(), month).isAfter(today);
    }
}
