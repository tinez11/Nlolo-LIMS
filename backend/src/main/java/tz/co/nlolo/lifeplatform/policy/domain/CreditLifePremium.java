package tz.co.nlolo.lifeplatform.policy.domain;

import tz.co.nlolo.lifeplatform.policy.api.CreditLifePremiumBasis;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * What one loan costs to insure, and what is owed back when it ends early.
 *
 * <p>Pure, like {@link AmortisationCalculator}, and for the same reason: this is the
 * arithmetic an argument with a lender will be about, so it must be assertable without a
 * database, a tenant or a Spring context.
 *
 * <p><b>Premium is charged on the ORIGINAL principal, even though cover declines.</b> That is
 * the client's practice, confirmed 2026-09-22, and it is not an oversight waiting to be
 * tidied up: the rate they negotiated was negotiated against the disbursed amount. A loan
 * whose cover halves over its term still costs the full rate on the full principal.
 *
 * <p>The rate itself belongs to the scheme, not to this class and not to the product —
 * it is what one lender agreed, and two lenders on the same filed product pay differently.
 */
public final class CreditLifePremium {

    /** Two decimal places, like every other money amount on the platform. */
    private static final int MONEY_SCALE = 2;

    /**
     * Enough precision that the only rounding is the final one.
     *
     * <p>A term of five months is 0.41666… years; rounding that to two places before
     * multiplying loses real money on every loan whose term is not a whole number of years,
     * which is most of a microlender's book.
     */
    private static final int WORKING_SCALE = 10;

    private static final BigDecimal MONTHS_PER_YEAR = new BigDecimal("12");
    private static final BigDecimal PERCENT = new BigDecimal("100");

    private CreditLifePremium() {}

    /**
     * The single premium one loan is charged at enrolment.
     *
     * @param principal the amount DISBURSED, not the balance outstanding
     * @param termMonths the loan's own term
     * @param ratePercent percent PER ANNUM — the scheme's rate, so 0.5000 means 0.5%
     */
    public static BigDecimal forLoan(BigDecimal principal, int termMonths, BigDecimal ratePercent) {
        return forLoan(principal, termMonths, ratePercent, CreditLifePremiumBasis.PER_ANNUM_ON_PRINCIPAL);
    }

    /**
     * The single premium one loan is charged at enrolment, on the basis its scheme agreed.
     *
     * <p>Three bases, because two real lenders priced two different ways and neither was the
     * one formula here. See {@link CreditLifePremiumBasis} for whose is whose; the worked
     * numbers that pin each of them live in {@code CreditLifePremiumTest}, taken from the
     * clients' own schedules rather than invented.
     *
     * <p>Every basis charges ONCE, at enrolment. The declining-balance basis reaches its figure
     * by walking policy years, which is how the lender's spreadsheet presents it, but the sum
     * is a single premium like the others — one file, one invoice, no arrears.
     *
     * @param principal the amount DISBURSED, not the balance outstanding
     * @param termMonths the loan's own term
     * @param ratePercent percent — the scheme's rate, so 0.5000 means 0.5%. Per annum on the
     *     two per-annum bases; the whole price on {@code FLAT_ON_PRINCIPAL}
     * @param basis how that rate becomes money
     */
    public static BigDecimal forLoan(BigDecimal principal, int termMonths, BigDecimal ratePercent,
                                      CreditLifePremiumBasis basis) {
        if (principal == null || principal.signum() <= 0) {
            throw new IllegalArgumentException("A loan with no principal cannot be priced");
        }
        if (termMonths <= 0) {
            throw new IllegalArgumentException("A loan with no term cannot be priced");
        }
        if (ratePercent == null || ratePercent.signum() <= 0) {
            throw new IllegalArgumentException(
                "A premium rate of " + ratePercent + " would write free cover");
        }
        if (basis == null) {
            throw new IllegalArgumentException(
                "A loan cannot be priced without knowing what its scheme's rate means; see "
                    + "CreditLifePremiumBasis");
        }

        BigDecimal rate = ratePercent.divide(PERCENT, WORKING_SCALE, RoundingMode.HALF_UP);

        return switch (basis) {
            case FLAT_ON_PRINCIPAL -> principal.multiply(rate)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);

            case PER_ANNUM_ON_PRINCIPAL -> {
                BigDecimal years = new BigDecimal(termMonths)
                    .divide(MONTHS_PER_YEAR, WORKING_SCALE, RoundingMode.HALF_UP);
                yield principal.multiply(rate).multiply(years)
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            }

            // One charge per policy year, each on what is still outstanding when that year
            // begins. Straight-line, so the outstanding share at the start of year y is the
            // months still to run over the whole term -- year 1 is the full principal, because
            // nothing has been repaid yet.
            //
            // Summed at WORKING_SCALE and rounded once at the end, like the other two: rounding
            // each year to the cent first would drift on a five-year loan, and the lender's own
            // sheet carries its yearly figures to ten decimal places precisely because it does
            // not round them either.
            case ANNUAL_ON_DECLINING_BALANCE -> {
                BigDecimal term = new BigDecimal(termMonths);
                BigDecimal total = BigDecimal.ZERO;
                for (int monthsRemaining = termMonths; monthsRemaining > 0; monthsRemaining -= 12) {
                    BigDecimal outstanding = principal
                        .multiply(new BigDecimal(monthsRemaining))
                        .divide(term, WORKING_SCALE, RoundingMode.HALF_UP);
                    total = total.add(outstanding.multiply(rate));
                }
                yield total.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            }
        };
    }

    /**
     * The unexpired share of a premium already charged, at the date a loan left.
     *
     * <p>Straight-line on ELAPSED MONTHS, which is the basis cover itself declines on. The two
     * must agree: refunding on a different basis than the one cover ran down on is how a
     * refund and a claim can disagree about the same loan on the same day.
     *
     * <p>A part month counts as unexpired rather than elapsed — {@link ChronoUnit#MONTHS}
     * truncates — so a borrower is never charged for a month they did not complete.
     *
     * <p>Never negative. A loan that ran its full term refunds nothing, and an exit dated
     * after maturity returns zero rather than a demand for money; a late exits file, where the
     * loan ended in March and the file arrives in June, is entirely ordinary.
     */
    public static BigDecimal unearnedAt(BigDecimal premiumCharged, int termMonths,
                                         LocalDate disbursedOn, LocalDate exitOn) {
        if (exitOn.isBefore(disbursedOn)) {
            throw new IllegalArgumentException("A loan cannot leave on " + exitOn
                + ", before it was disbursed on " + disbursedOn);
        }
        long elapsed = ChronoUnit.MONTHS.between(disbursedOn, exitOn);
        long remaining = Math.max(0, termMonths - elapsed);
        if (remaining == 0) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE);
        }
        return premiumCharged
            .multiply(new BigDecimal(remaining))
            .divide(new BigDecimal(termMonths), MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
