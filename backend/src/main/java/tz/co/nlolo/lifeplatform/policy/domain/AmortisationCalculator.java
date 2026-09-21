package tz.co.nlolo.lifeplatform.policy.domain;

import tz.co.nlolo.lifeplatform.policy.api.InterestMethod;
import tz.co.nlolo.lifeplatform.policy.api.LoanTerms;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * The one place a credit-life sum assured is decided.
 *
 * <p>A pure function over plain values, deliberately -- the same reasoning as
 * {@link GroupBenefitCalculator}: this decides what a dead borrower's debt is worth to a
 * lender, and it must be exercisable against fixtures with no database and no Spring.
 *
 * <p><b>Nothing here is stored.</b> Materialising one row per repayment date would be
 * tens of thousands of rows for a single enrolment file, on a table built for occasional
 * restatement. The computed figure IS snapshotted onto a claim when one is registered,
 * but that is the claim's job, not this class's.
 */
public final class AmortisationCalculator {

    /** Working precision for the intermediate powers; every result is rounded to 2dp. */
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal MONTHS_PER_YEAR = BigDecimal.valueOf(12);

    private AmortisationCalculator() {}

    /**
     * The instalment this loan carries under a given method.
     *
     * <p>Reducing balance is the standard annuity, {@code P*r / (1 - (1+r)^-n)}. Flat rate
     * spreads principal plus total interest evenly, where the interest is charged on the
     * ORIGINAL principal for the whole term regardless of what has been repaid.
     */
    public static BigDecimal instalmentFor(LoanTerms terms, InterestMethod method) {
        int n = terms.numberOfInstalments();
        BigDecimal principal = terms.principalAmount();

        return switch (method) {
            case FLAT_RATE -> {
                BigDecimal years = BigDecimal.valueOf(terms.termMonths()).divide(MONTHS_PER_YEAR, MC);
                BigDecimal interest = principal
                    .multiply(terms.annualInterestRatePercent().divide(HUNDRED, MC), MC)
                    .multiply(years, MC);
                yield principal.add(interest).divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
            }
            case REDUCING_BALANCE -> {
                BigDecimal r = periodicRate(terms);
                if (r.signum() == 0) {
                    yield principal.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
                }
                BigDecimal growth = BigDecimal.ONE.add(r).pow(n, MC);
                // P*r / (1 - (1+r)^-n)  ==  P*r*growth / (growth - 1)
                yield principal.multiply(r, MC).multiply(growth, MC)
                    .divide(growth.subtract(BigDecimal.ONE), 2, RoundingMode.HALF_UP);
            }
        };
    }

    /**
     * The principal still owed on a given date -- what the lender loses, and therefore
     * what credit life insures.
     *
     * <p>Before the first repayment the whole principal is outstanding, so a moratorium
     * needs no special case. After the last, zero. The result is never negative.
     */
    public static BigDecimal outstandingPrincipalAt(LoanTerms terms, InterestMethod method,
                                                     LocalDate asOf) {
        int n = terms.numberOfInstalments();
        int paid = instalmentsPaidBy(terms, asOf);
        if (paid <= 0) return terms.principalAmount().setScale(2, RoundingMode.HALF_UP);
        if (paid >= n) return BigDecimal.ZERO.setScale(2);

        BigDecimal principal = terms.principalAmount();
        BigDecimal remaining = BigDecimal.valueOf(n - (long) paid);
        BigDecimal balance = switch (method) {
            // Flat rate repays principal in n equal slices -- the interest is a separate,
            // fixed charge that does not change what is owed on the capital. This is a
            // straight line, and it reads no interest rate, which is what makes it
            // computable from a client file that states none.
            case FLAT_RATE -> principal.multiply(remaining, MC).divide(BigDecimal.valueOf(n), MC);
            // B_k = P*(1+r)^k - I*((1+r)^k - 1)/r
            case REDUCING_BALANCE -> {
                BigDecimal r = periodicRate(terms);
                if (r.signum() == 0) {
                    yield principal.multiply(remaining, MC).divide(BigDecimal.valueOf(n), MC);
                }
                // The ROUNDED instalment, because that is what the borrower actually pays.
                BigDecimal instalment = instalmentFor(terms, InterestMethod.REDUCING_BALANCE);
                BigDecimal growth = BigDecimal.ONE.add(r).pow(paid, MC);
                yield principal.multiply(growth, MC)
                    .subtract(instalment.multiply(growth.subtract(BigDecimal.ONE), MC).divide(r, MC), MC);
            }
        };

        BigDecimal rounded = balance.setScale(2, RoundingMode.HALF_UP);
        return rounded.signum() < 0 ? BigDecimal.ZERO.setScale(2) : rounded;
    }

    /**
     * How many scheduled repayment dates fall on or before {@code asOf}.
     *
     * <p>Counted in whole periods, not months: a quarterly loan's second instalment falls
     * three months after its first.
     */
    private static int instalmentsPaidBy(LoanTerms terms, LocalDate asOf) {
        if (asOf.isBefore(terms.firstRepaymentDate())) return 0;
        long monthsSinceFirst = ChronoUnit.MONTHS.between(terms.firstRepaymentDate(), asOf);
        long paid = monthsSinceFirst / terms.repaymentFrequency().monthsPerPeriod() + 1;
        return (int) Math.min(paid, terms.numberOfInstalments());
    }

    private static BigDecimal periodicRate(LoanTerms terms) {
        return terms.annualInterestRatePercent()
            .divide(HUNDRED, MC)
            .divide(BigDecimal.valueOf(terms.repaymentFrequency().periodsPerYear()), MC);
    }
}
