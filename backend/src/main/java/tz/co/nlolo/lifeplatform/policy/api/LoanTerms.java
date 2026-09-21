package tz.co.nlolo.lifeplatform.policy.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Everything about one loan that the insurer needs, and nothing it does not.
 *
 * <p>There is no interest-method field: the method belongs to the lender, not to the
 * individual loan, so it is configured once on the scheme. There is no instalment field
 * either -- neither real client schedule carries one.
 *
 * @param annualInterestRatePercent may be zero. Both real lenders' files omit the rate
 *     entirely, and straight-line decline never reads it.
 * @param firstRepaymentDate later than one period after disbursement means a moratorium.
 *     No separate field expresses one, and none is needed: the gap between the two dates
 *     IS the payment holiday, and the schedule already holds the balance flat across it.
 */
public record LoanTerms(BigDecimal principalAmount, BigDecimal annualInterestRatePercent,
                         int termMonths, RepaymentFrequency repaymentFrequency,
                         LocalDate disbursementDate, LocalDate firstRepaymentDate) {

    public LoanTerms {
        if (principalAmount == null || principalAmount.signum() <= 0) {
            throw new IllegalArgumentException("A loan must have a positive principal");
        }
        if (annualInterestRatePercent == null || annualInterestRatePercent.signum() < 0) {
            throw new IllegalArgumentException("A loan's interest rate may not be negative");
        }
        if (termMonths <= 0) {
            throw new IllegalArgumentException("A loan must have a positive term");
        }
        if (repaymentFrequency == null) {
            throw new IllegalArgumentException("A loan must state its repayment frequency");
        }
        // Refused rather than rounded: a 17-month quarterly loan has no whole schedule,
        // and silently treating it as five or six periods would misstate every balance
        // for the life of the cover.
        if (termMonths % repaymentFrequency.monthsPerPeriod() != 0) {
            throw new IllegalArgumentException("A " + termMonths + "-month term does not divide into "
                + repaymentFrequency + " periods");
        }
        if (disbursementDate == null || firstRepaymentDate == null) {
            throw new IllegalArgumentException("A loan must carry both of its dates");
        }
        if (firstRepaymentDate.isBefore(disbursementDate)) {
            throw new IllegalArgumentException("A loan cannot be repaid before it is disbursed");
        }
    }

    /** How many instalments the schedule contains. */
    public int numberOfInstalments() {
        return termMonths / repaymentFrequency.monthsPerPeriod();
    }
}
