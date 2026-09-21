package tz.co.nlolo.lifeplatform.policy.api;

/**
 * How often a loan is repaid.
 *
 * <p>Both values are needed by the schedule and they are not interchangeable: a 36-month
 * loan repaid quarterly has twelve instalments, not thirty-six, and counting months would
 * report a loan nearly repaid when a quarter of it has been.
 */
public enum RepaymentFrequency {

    MONTHLY(12, 1),
    QUARTERLY(4, 3);

    private final int periodsPerYear;
    private final int monthsPerPeriod;

    RepaymentFrequency(int periodsPerYear, int monthsPerPeriod) {
        this.periodsPerYear = periodsPerYear;
        this.monthsPerPeriod = monthsPerPeriod;
    }

    /** Compounding periods in a year, for converting an annual rate to a periodic one. */
    public int periodsPerYear() { return periodsPerYear; }

    /** Months between instalments, for counting how many have fallen due. */
    public int monthsPerPeriod() { return monthsPerPeriod; }
}
