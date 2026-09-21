package tz.co.nlolo.lifeplatform.policy;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.policy.api.InterestMethod;
import tz.co.nlolo.lifeplatform.policy.api.LoanTerms;
import tz.co.nlolo.lifeplatform.policy.api.RepaymentFrequency;
import tz.co.nlolo.lifeplatform.policy.domain.AmortisationCalculator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The one place a credit-life balance is decided, so it is tested as a pure function with
 * no database and no Spring context -- the same reasoning as GroupBenefitCalculatorTest.
 *
 * <p>Every expected figure was derived from the annuity and flat-rate formulas
 * independently of this implementation. They are fixtures, not recordings of whatever the
 * code happened to produce: if one disagrees, suspect the code first.
 */
class AmortisationCalculatorTest {

    private static BigDecimal money(String amount) {
        return new BigDecimal(amount);
    }

    /** 8,500,000 TZS at 18.5% over 48 monthly instalments, disbursed 2026-08-03. */
    private static LoanTerms loan() {
        return new LoanTerms(money("8500000.00"), money("18.50"), 48,
            RepaymentFrequency.MONTHLY,
            LocalDate.of(2026, 8, 3), LocalDate.of(2026, 9, 3));
    }

    /**
     * LOLC's real shape, from the June 2026 schedule: 10,400,000 over 18 months, disbursed
     * 2026-06-30. Their file states no interest rate at all, which is the case that
     * matters -- see this class's straight-line test.
     */
    private static LoanTerms lolcLoan() {
        return new LoanTerms(money("10400000.00"), BigDecimal.ZERO, 18,
            RepaymentFrequency.MONTHLY,
            LocalDate.of(2026, 6, 30), LocalDate.of(2026, 7, 30));
    }

    // ---- instalmentFor ------------------------------------------------------

    @Test
    void reducingBalanceInstalmentMatchesTheAnnuityFormula() {
        BigDecimal actual = AmortisationCalculator.instalmentFor(
            loan(), InterestMethod.REDUCING_BALANCE);
        assertEquals(0, money("251913.79").compareTo(actual), "was " + actual);
    }

    @Test
    void flatRateInstalmentSpreadsPrincipalPlusTotalInterestEvenly() {
        // 8,500,000 + (8,500,000 x 18.5% x 4 years) = 14,790,000 over 48 instalments.
        BigDecimal actual = AmortisationCalculator.instalmentFor(
            loan(), InterestMethod.FLAT_RATE);
        assertEquals(0, money("308125.00").compareTo(actual), "was " + actual);
    }

    @Test
    void theTwoMethodsAreNeverCloseEnoughToConfuse() {
        // Kept from a withdrawn design that tried to tell the methods apart from a
        // lender's own stated instalment. The property still matters: if these ever
        // converged, any future attempt to do that would be unsafe. Here the gap is 22%.
        BigDecimal reducing = AmortisationCalculator.instalmentFor(loan(), InterestMethod.REDUCING_BALANCE);
        BigDecimal flat = AmortisationCalculator.instalmentFor(loan(), InterestMethod.FLAT_RATE);
        BigDecimal gap = flat.subtract(reducing).abs().divide(reducing, 4, RoundingMode.HALF_UP);
        assertTrue(gap.compareTo(new BigDecimal("0.10")) > 0, "gap was " + gap);
    }

    // ---- outstandingPrincipalAt ---------------------------------------------

    @Test
    void balanceBeforeTheFirstRepaymentIsTheFullPrincipal() {
        BigDecimal actual = AmortisationCalculator.outstandingPrincipalAt(
            loan(), InterestMethod.REDUCING_BALANCE, LocalDate.of(2026, 8, 20));
        assertEquals(0, money("8500000.00").compareTo(actual), "was " + actual);
    }

    @Test
    void aMoratoriumHoldsTheBalanceAtTheFullPrincipal() {
        // Disbursed 2026-08-07, first repayment 2026-11-07: a three-month holiday. No
        // separate field expresses one; the gap between the two dates is the moratorium.
        LoanTerms moratorium = new LoanTerms(money("45000000.00"), money("15.50"), 120,
            RepaymentFrequency.MONTHLY,
            LocalDate.of(2026, 8, 7), LocalDate.of(2026, 11, 7));
        BigDecimal actual = AmortisationCalculator.outstandingPrincipalAt(
            moratorium, InterestMethod.REDUCING_BALANCE, LocalDate.of(2026, 10, 31));
        assertEquals(0, money("45000000.00").compareTo(actual), "was " + actual);
    }

    @Test
    void flatRateRepaysPrincipalInEqualSlices() {
        // 24 of 48 instalments paid by 2028-08-03: exactly half the principal left. This
        // IS straight-line decline.
        BigDecimal actual = AmortisationCalculator.outstandingPrincipalAt(
            loan(), InterestMethod.FLAT_RATE, LocalDate.of(2028, 8, 3));
        assertEquals(0, money("4250000.00").compareTo(actual), "was " + actual);
    }

    @Test
    void straightLineDeclineNeedsNoInterestRateAtAll() {
        // The case the real client files present: no rate anywhere on the schedule.
        // FLAT_RATE's principal decline never reads the rate, so a zero rate still
        // produces a correct straight line -- 9 of 18 instalments paid by 2027-03-30.
        BigDecimal actual = AmortisationCalculator.outstandingPrincipalAt(
            lolcLoan(), InterestMethod.FLAT_RATE, LocalDate.of(2027, 3, 30));
        assertEquals(0, money("5200000.00").compareTo(actual), "was " + actual);
    }

    @Test
    void reducingBalanceRepaysPrincipalSlowlyAtFirst() {
        // B_k = P(1+r)^k - I((1+r)^k - 1)/r, with k=24, r=0.185/12, I=251913.79.
        // After half the payments a reducing-balance loan still owes 59% of principal,
        // against the flat-rate 50% -- early instalments are mostly interest. Insuring
        // the flat figure on a reducing-balance book would underpay every claim.
        BigDecimal actual = AmortisationCalculator.outstandingPrincipalAt(
            loan(), InterestMethod.REDUCING_BALANCE, LocalDate.of(2028, 8, 3));
        assertEquals(0, money("5021601.32").compareTo(actual), "was " + actual);
        assertTrue(actual.compareTo(money("4250000.00")) > 0,
            "reducing balance must exceed the flat-rate midpoint, was " + actual);
    }

    @Test
    void balanceAtTheEndOfTheTermIsZeroAndNeverNegative() {
        for (InterestMethod method : InterestMethod.values()) {
            BigDecimal atMaturity = AmortisationCalculator.outstandingPrincipalAt(
                loan(), method, LocalDate.of(2030, 9, 3));
            assertEquals(0, BigDecimal.ZERO.compareTo(atMaturity), method + " was " + atMaturity);

            BigDecimal wellAfter = AmortisationCalculator.outstandingPrincipalAt(
                loan(), method, LocalDate.of(2040, 1, 1));
            assertEquals(0, BigDecimal.ZERO.compareTo(wellAfter), method + " was " + wellAfter);
        }
    }

    @Test
    void quarterlyRepaymentsCountQuartersNotMonths() {
        // 12,750,000 over 36 months paid QUARTERLY is 12 instalments, not 36. Counting
        // months would call 4 payments 12 and report the loan nearly repaid.
        LoanTerms quarterly = new LoanTerms(money("12750000.00"), money("19.00"), 36,
            RepaymentFrequency.QUARTERLY,
            LocalDate.of(2026, 8, 10), LocalDate.of(2026, 11, 10));

        assertEquals(0, money("1418312.37").compareTo(
            AmortisationCalculator.instalmentFor(quarterly, InterestMethod.REDUCING_BALANCE)));

        BigDecimal afterFour = AmortisationCalculator.outstandingPrincipalAt(
            quarterly, InterestMethod.FLAT_RATE, LocalDate.of(2027, 8, 10));
        assertEquals(0, money("8500000.00").compareTo(afterFour), "was " + afterFour);
    }

    // ---- LoanTerms invariants -----------------------------------------------

    @Test
    void aTermThatDoesNotDivideIntoPeriodsIsRefused() {
        // 18 months quarterly is 6 periods; 17 months quarterly is not a schedule.
        assertThrows(IllegalArgumentException.class, () -> new LoanTerms(
            money("1000000.00"), money("18.00"), 17, RepaymentFrequency.QUARTERLY,
            LocalDate.of(2026, 8, 3), LocalDate.of(2026, 11, 3)));
    }

    @Test
    void aLoanRepaidBeforeItIsDisbursedIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new LoanTerms(
            money("1000000.00"), money("18.00"), 12, RepaymentFrequency.MONTHLY,
            LocalDate.of(2026, 8, 3), LocalDate.of(2026, 7, 3)));
    }
}
