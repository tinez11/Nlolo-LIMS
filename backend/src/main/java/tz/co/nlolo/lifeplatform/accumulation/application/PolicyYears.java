package tz.co.nlolo.lifeplatform.accumulation.application;

import java.time.LocalDate;

/**
 * Which policy year a date falls in -- the key every charge row is looked up by.
 *
 * <p>Anniversaries are {@code commencement.plusYears(n)}, the rule benefitpayout's schedule dates
 * them by, and NOT {@code Period.between}. The two disagree for a 29 February start: plusYears puts
 * the first anniversary on 28 February, while Period.between still calls that day year 1. Two
 * modules counting years two ways would charge a year-1 fee on a date the payout schedule already
 * treats as year 2.
 */
public final class PolicyYears {
    private PolicyYears() {}

    public static int of(LocalDate commencement, LocalDate on) {
        if (on.isBefore(commencement)) {
            throw new IllegalArgumentException(on + " is before cover began on " + commencement);
        }
        int completed = on.getYear() - commencement.getYear();
        if (commencement.plusYears(completed).isAfter(on)) {
            completed--;
        }
        return completed + 1;
    }
}
