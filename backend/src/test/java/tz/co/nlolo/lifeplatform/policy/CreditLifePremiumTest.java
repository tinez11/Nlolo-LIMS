package tz.co.nlolo.lifeplatform.policy;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.policy.api.CreditLifePremiumBasis;
import tz.co.nlolo.lifeplatform.policy.domain.CreditLifePremium;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What one loan costs to insure, and what is owed back when it ends early.
 *
 * <p>Pure, like {@code AmortisationCalculatorTest}, and for the same reason: this is the
 * arithmetic an argument with a lender will be about. It must be assertable without a
 * database, a tenant or a Spring context, and readable by somebody checking it against a
 * spreadsheet.
 */
class CreditLifePremiumTest {

    // ---- the bases, against the clients' own schedules -----------------------
    //
    // Every figure below is read off a real file in `sample data/`, not invented. That is the
    // point of them: the platform priced on one formula for a long time and it matched neither
    // lender, which no fixture of my own devising would ever have caught.

    /**
     * BUMACO INSURANCE MAY.xlsx, all six loans on it, at the 0.6% their sheet works out at.
     *
     * <p>Terms of 2, 4, 6, 6, 12 and 12 months, and every one is the same flat percentage of
     * the disbursed amount: the 2-month loan pays exactly what a 12-month loan of its size
     * does. The sheet's own total is 111,000 on 18,500,000 of principal, which is what these
     * six sum to.
     */
    @Test
    void aFlatBasisChargesTheRateOnTheDisbursedAmountWhateverTheTerm() {
        BigDecimal rate = new BigDecimal("0.6000");
        record Loan(String principal, int termMonths, String premium) {}
        List<Loan> may = List.of(
            new Loan("1500000.00", 4, "9000.00"),    // Malunde
            new Loan("6000000.00", 12, "36000.00"),  // Chami
            new Loan("1000000.00", 6, "6000.00"),    // Sendwa
            new Loan("1000000.00", 2, "6000.00"),    // Malipesa -- two months, full rate
            new Loan("4000000.00", 6, "24000.00"),   // Kipagata
            new Loan("5000000.00", 12, "30000.00"));  // Mselle

        BigDecimal total = BigDecimal.ZERO;
        for (Loan loan : may) {
            BigDecimal premium = CreditLifePremium.forLoan(new BigDecimal(loan.principal()),
                loan.termMonths(), rate, CreditLifePremiumBasis.FLAT_ON_PRINCIPAL);
            assertThat(premium).as(loan.principal() + " over " + loan.termMonths() + " months")
                .isEqualByComparingTo(loan.premium());
            total = total.add(premium);
        }
        assertThat(total).as("the file's own total").isEqualByComparingTo("111000.00");
    }

    /**
     * CREDIT LIFE INSURANCE LOLC EXCEL SHEET-JUNE 2026.xlsx, at their 0.5%.
     *
     * <p>The rate once per policy year, each year on what is still outstanding when it begins.
     * The 23-month row is the one that proves the shape rather than merely fitting it: its
     * second year is 10,500,000 x (11/23) x 0.5% = 25,108.6956..., and the sheet carries that
     * to ten decimal places.
     */
    @Test
    void aDecliningBalanceBasisChargesEachYearOnWhatIsStillOwed() {
        BigDecimal rate = new BigDecimal("0.5000");
        record Loan(String principal, int termMonths, String total, String why) {}
        List<Loan> june = List.of(
            new Loan("10400000.00", 12, "52000.00", "one year only, nothing has declined"),
            new Loan("10400000.00", 18, "69333.33", "52,000 then 10,400,000 x 6/18 x 0.5%"),
            new Loan("10500000.00", 18, "70000.00", "52,500 then 17,500"),
            new Loan("5900000.00", 18, "39333.33", "29,500 then 9,833.33"),
            new Loan("10500000.00", 23, "77608.70", "52,500 then 25,108.6956..."),
            new Loan("10500000.00", 24, "78750.00", "52,500 then half of 52,500"));

        for (Loan loan : june) {
            assertThat(CreditLifePremium.forLoan(new BigDecimal(loan.principal()),
                    loan.termMonths(), rate, CreditLifePremiumBasis.ANNUAL_ON_DECLINING_BALANCE))
                .as(loan.why())
                .isEqualByComparingTo(loan.total());
        }
    }

    /**
     * The three bases genuinely differ, so choosing the wrong one is not a rounding matter.
     *
     * <p>Same loan, same rate: a flat basis charges 52,000, the platform's original per-annum
     * basis charges 78,000, and LOLC's declining basis 69,333.33. The 26,000 between the first
     * and the second is the gap that went unnoticed, because it closes to zero on a 12-month
     * loan and every fixture used one.
     */
    @Test
    void theBasesDisagreeOnEveryTermThatIsNotExactlyOneYear() {
        BigDecimal principal = new BigDecimal("10400000.00");
        BigDecimal rate = new BigDecimal("0.5000");

        assertThat(CreditLifePremium.forLoan(principal, 18, rate, CreditLifePremiumBasis.FLAT_ON_PRINCIPAL))
            .isEqualByComparingTo("52000.00");
        assertThat(CreditLifePremium.forLoan(principal, 18, rate, CreditLifePremiumBasis.PER_ANNUM_ON_PRINCIPAL))
            .isEqualByComparingTo("78000.00");
        assertThat(CreditLifePremium.forLoan(principal, 18, rate, CreditLifePremiumBasis.ANNUAL_ON_DECLINING_BALANCE))
            .isEqualByComparingTo("69333.33");

        // At exactly twelve months they collapse onto one another, which is why a book of
        // annual loans hides the difference completely.
        for (CreditLifePremiumBasis basis : CreditLifePremiumBasis.values()) {
            assertThat(CreditLifePremium.forLoan(principal, 12, rate, basis))
                .as(basis.name() + " at twelve months")
                .isEqualByComparingTo("52000.00");
        }
    }

    @Test
    void theDefaultOverloadStaysOnThePlatformsOriginalBasis() {
        // 45 schemes were priced through the three-argument call before a basis existed, and it
        // must go on meaning what it meant to them.
        assertThat(CreditLifePremium.forLoan(new BigDecimal("10400000.00"), 18, new BigDecimal("0.5000")))
            .isEqualByComparingTo(CreditLifePremium.forLoan(new BigDecimal("10400000.00"), 18,
                new BigDecimal("0.5000"), CreditLifePremiumBasis.PER_ANNUM_ON_PRINCIPAL));
    }

    @Test
    void aLoanCannotBePricedWithoutKnowingWhatItsRateMeans() {
        assertThatThrownBy(() -> CreditLifePremium.forLoan(
                new BigDecimal("1000000.00"), 12, new BigDecimal("0.5000"), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("CreditLifePremiumBasis");
    }

    // ---- what a loan costs --------------------------------------------------

    @Test
    void premiumIsTheRateTimesThePrincipalTimesTheYearsOfCover() {
        // 8,500,000 at 0.5% per annum over 48 months, which is 4 years.
        // 8,500,000 x 0.005 x 4 = 170,000.
        assertThat(CreditLifePremium.forLoan(
                new BigDecimal("8500000.00"), 48, new BigDecimal("0.5000")))
            .isEqualByComparingTo("170000.00");
    }

    @Test
    void aPartYearIsChargedProRataAndNotRoundedUpToAWholeYear() {
        // 18 months is 1.5 years: 2,400,000 x 0.005 x 1.5 = 18,000.
        // Rounding up to two years would overcharge every short loan by a third, and short
        // loans are most of a microlender's book.
        assertThat(CreditLifePremium.forLoan(
                new BigDecimal("2400000.00"), 18, new BigDecimal("0.5000")))
            .isEqualByComparingTo("18000.00");
    }

    @Test
    void theRateIsPerLenderSoTwoLendersPayDifferentlyForTheSameLoan() {
        // The client quoted 0.4% and 0.5%. Same borrower, same loan, different scheme.
        BigDecimal cheaper = CreditLifePremium.forLoan(
            new BigDecimal("10000000.00"), 12, new BigDecimal("0.4000"));
        BigDecimal dearer = CreditLifePremium.forLoan(
            new BigDecimal("10000000.00"), 12, new BigDecimal("0.5000"));

        assertThat(cheaper).isEqualByComparingTo("40000.00");
        assertThat(dearer).isEqualByComparingTo("50000.00");
    }

    @Test
    void thePremiumIsOnTheOriginalPrincipalEvenThoughCoverDeclines() {
        // Cover falls straight-line, but premium does not follow it down: the client charges
        // on the disbursed amount, and the rate they negotiated was negotiated against it.
        //
        // This test exists because "cover declines, so premium should decline" is the most
        // plausible wrong turn in this whole feature. A 12-month loan at 1% costs 1% of the
        // principal -- not 1% of the average balance, which would be roughly half that.
        assertThat(CreditLifePremium.forLoan(
                new BigDecimal("1000000.00"), 12, new BigDecimal("1.0000")))
            .isEqualByComparingTo("10000.00");
    }

    @Test
    void moneyIsRoundedToTheCentAndNeverLeftAtFullScale() {
        // 6,000,000 x 0.0045 x 2.5 = 67,500.00.
        BigDecimal premium = CreditLifePremium.forLoan(
            new BigDecimal("6000000.00"), 30, new BigDecimal("0.4500"));

        assertThat(premium.scale()).isEqualTo(2);
        assertThat(premium).isEqualByComparingTo("67500.00");
    }

    @Test
    void aTermThatDoesNotDivideIntoYearsStillRoundsOnlyOnceAtTheEnd() {
        // 5 months at 0.5% on 1,000,000: 1,000,000 x 0.005 x (5/12) = 2,083.333...
        // Rounding the year fraction first (0.42) would give 2,100 -- out by 16.67 on one
        // small loan, and by a great deal more across four hundred of them.
        assertThat(CreditLifePremium.forLoan(
                new BigDecimal("1000000.00"), 5, new BigDecimal("0.5000")))
            .isEqualByComparingTo("2083.33");
    }

    @Test
    void aZeroOrNegativeRateIsRefusedRatherThanWritingFreeCover() {
        assertThatThrownBy(() -> CreditLifePremium.forLoan(
                new BigDecimal("1000000.00"), 12, BigDecimal.ZERO))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("free cover");

        assertThatThrownBy(() -> CreditLifePremium.forLoan(
                new BigDecimal("1000000.00"), 12, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aLoanWithNoPrincipalOrNoTermCannotBePriced() {
        assertThatThrownBy(() -> CreditLifePremium.forLoan(
                BigDecimal.ZERO, 12, new BigDecimal("0.5000")))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> CreditLifePremium.forLoan(
                new BigDecimal("1000000.00"), 0, new BigDecimal("0.5000")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- what is owed back --------------------------------------------------

    @Test
    void aLoanSettledHalfwayThroughItsTermLeavesHalfItsPremiumUnearned() {
        // 18,000 charged over 18 months; nine months elapsed leaves nine unexpired.
        assertThat(CreditLifePremium.unearnedAt(new BigDecimal("18000.00"), 18,
                LocalDate.of(2026, 1, 15), LocalDate.of(2026, 10, 15)))
            .isEqualByComparingTo("9000.00");
    }

    @Test
    void aLoanThatRanItsFullTermLeavesNothingUnearned() {
        // Not a negative number, which would be a charge rather than a refund.
        assertThat(CreditLifePremium.unearnedAt(new BigDecimal("18000.00"), 18,
                LocalDate.of(2026, 1, 15), LocalDate.of(2027, 7, 15)))
            .isEqualByComparingTo("0.00");
    }

    @Test
    void anExitAfterMaturityStillReturnsZeroRatherThanANegativeRefund() {
        // A late exits file is ordinary: the loan ended in March and the file arrives in
        // June. Two years past maturity must not produce a demand for money.
        assertThat(CreditLifePremium.unearnedAt(new BigDecimal("18000.00"), 18,
                LocalDate.of(2026, 1, 15), LocalDate.of(2029, 1, 15)))
            .isEqualByComparingTo("0.00");
    }

    @Test
    void aLoanSettledTheDayItWasDisbursedLeavesTheWholePremiumUnearned() {
        // Nothing was ever on risk. The whole premium goes back.
        assertThat(CreditLifePremium.unearnedAt(new BigDecimal("18000.00"), 18,
                LocalDate.of(2026, 1, 15), LocalDate.of(2026, 1, 15)))
            .isEqualByComparingTo("18000.00");
    }

    @Test
    void aPartMonthCountsAsUnexpiredRatherThanElapsed() {
        // Exiting one day short of the second month is still one month elapsed, not two.
        // Counting it as two would short the borrower a month of refund on every exit.
        assertThat(CreditLifePremium.unearnedAt(new BigDecimal("1200.00"), 12,
                LocalDate.of(2026, 1, 15), LocalDate.of(2026, 3, 14)))
            .isEqualByComparingTo("1100.00");
    }

    @Test
    void anExitBeforeDisbursementIsRefusedRatherThanRefundingMoreThanWasCharged() {
        assertThatThrownBy(() -> CreditLifePremium.unearnedAt(new BigDecimal("18000.00"), 18,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 5, 1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("before it was disbursed");
    }

    @Test
    void theRefundBasisMatchesTheBasisCoverItselfDeclinesOn() {
        // Both straight-line on elapsed months. Refunding on a different basis than the one
        // cover ran down on is how a refund and a claim can disagree about the same loan on
        // the same day: at month 6 of 12, exactly half the cover remains and exactly half
        // the premium is unearned.
        BigDecimal unearned = CreditLifePremium.unearnedAt(new BigDecimal("10000.00"), 12,
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 1));

        assertThat(unearned).isEqualByComparingTo("5000.00");
    }

    @Test
    void unearnedAmountsAcrossAWholeFileNeverExceedWhatWasCharged() {
        // The property that matters once four hundred borrowers exit one at a time. Each
        // refund is rounded to the cent, so the sum must be checked rather than assumed.
        BigDecimal charged = new BigDecimal("100.00");
        BigDecimal atMonthOne = CreditLifePremium.unearnedAt(charged, 3,
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1));
        BigDecimal atMonthTwo = CreditLifePremium.unearnedAt(charged, 3,
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 1));

        // 2/3 and 1/3 of 100.00 -- neither divides evenly, and neither may round up past it.
        assertThat(atMonthOne).isEqualByComparingTo("66.67");
        assertThat(atMonthTwo).isEqualByComparingTo("33.33");
        assertThat(atMonthOne).isLessThanOrEqualTo(charged);
    }
}
