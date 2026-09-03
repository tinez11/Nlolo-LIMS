package tz.co.nlolo.lifeplatform.policy;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.policy.api.BenefitBasis;
import tz.co.nlolo.lifeplatform.policy.api.MemberUnderwritingStatus;
import tz.co.nlolo.lifeplatform.policy.domain.GroupBenefitCalculator;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The only place that decides what a group member is insured for, so it is tested as a
 * pure function with no database and no Spring context.
 */
class GroupBenefitCalculatorTest {

    private static BigDecimal money(String amount) {
        return new BigDecimal(amount);
    }

    // ---- benefitFor ---------------------------------------------------------

    @Test
    void flatSchemeGivesEveryMemberTheSchemeAmount() {
        BigDecimal benefit = GroupBenefitCalculator.benefitFor(
            BenefitBasis.FLAT, money("5000000.00"), null, null, null);
        assertEquals(0, money("5000000.00").compareTo(benefit));
    }

    @Test
    void salaryMultipleGivesSalaryTimesTheMultiple() {
        // The client's own example: 5x annual salary.
        BigDecimal benefit = GroupBenefitCalculator.benefitFor(
            BenefitBasis.SALARY_MULTIPLE, null, money("5"), money("30000000.00"), null);
        assertEquals(0, money("150000000.00").compareTo(benefit));
    }

    /**
     * Rounded once at the end, HALF_UP to 2dp -- the same rule quotePremium follows. A
     * fractional multiple on an odd salary otherwise carries sub-cent amounts into a sum
     * assured, which then flows to claims and the general ledger.
     */
    @Test
    void salaryMultipleRoundsOnceToTwoPlaces() {
        BigDecimal benefit = GroupBenefitCalculator.benefitFor(
            BenefitBasis.SALARY_MULTIPLE, null, money("3.5"), money("1234567.89"), null);
        assertEquals(0, money("4320987.62").compareTo(benefit), "expected 1234567.89 * 3.5 rounded HALF_UP");
    }

    @Test
    void gradedSchemeTakesTheGradeAmount() {
        BigDecimal benefit = GroupBenefitCalculator.benefitFor(
            BenefitBasis.GRADED, null, null, null, money("20000000.00"));
        assertEquals(0, money("20000000.00").compareTo(benefit));
    }

    /**
     * Guessing here would put a fabricated death benefit on a real contract, so each
     * basis refuses rather than defaulting when its own input is missing.
     */
    @Test
    void aBasisWithoutItsInputRefusesRatherThanGuessing() {
        assertThrows(IllegalArgumentException.class, () -> GroupBenefitCalculator.benefitFor(
            BenefitBasis.FLAT, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> GroupBenefitCalculator.benefitFor(
            BenefitBasis.SALARY_MULTIPLE, null, money("5"), null, null), "no salary");
        assertThrows(IllegalArgumentException.class, () -> GroupBenefitCalculator.benefitFor(
            BenefitBasis.SALARY_MULTIPLE, null, null, money("30000000.00"), null), "no multiple");
        assertThrows(IllegalArgumentException.class, () -> GroupBenefitCalculator.benefitFor(
            BenefitBasis.GRADED, null, null, null, null), "grade not on the scheme table");
    }

    // ---- evaluate (the free cover limit) ------------------------------------

    @Test
    void aBenefitWithinTheLimitIsFullyCoveredWithNoEvidence() {
        var valuation = GroupBenefitCalculator.evaluate(money("80000000.00"), money("100000000.00"));
        assertEquals(MemberUnderwritingStatus.WITHIN_FCL, valuation.underwritingStatus());
        assertEquals(0, money("80000000.00").compareTo(valuation.coveredAmount()));
    }

    @Test
    void aBenefitExactlyAtTheLimitIsStillWithinIt() {
        var valuation = GroupBenefitCalculator.evaluate(money("100000000.00"), money("100000000.00"));
        assertEquals(MemberUnderwritingStatus.WITHIN_FCL, valuation.underwritingStatus());
    }

    /**
     * The rule that matters most: a member above the limit is covered UP TO IT
     * immediately, not left uninsured while evidence is outstanding. The client's own
     * example -- Mary at 150m against a 100m FCL -- is insured for 100m from day one.
     */
    @Test
    void aBenefitAboveTheLimitIsCoveredAtTheLimitWhileEvidenceIsOutstanding() {
        var valuation = GroupBenefitCalculator.evaluate(money("150000000.00"), money("100000000.00"));
        assertEquals(MemberUnderwritingStatus.EVIDENCE_REQUIRED, valuation.underwritingStatus());
        assertEquals(0, money("100000000.00").compareTo(valuation.coveredAmount()));
        assertEquals(0, money("150000000.00").compareTo(valuation.benefitAmount()),
            "the full benefit is still recorded -- only the covered amount is capped");
    }

    /**
     * No FCL is a real scheme design -- small flat schemes commonly have none -- and it
     * is NOT the same as a limit of zero, which would send every member to underwriting.
     */
    @Test
    void aSchemeWithNoLimitCoversTheFullBenefit() {
        var valuation = GroupBenefitCalculator.evaluate(money("500000000.00"), null);
        assertEquals(MemberUnderwritingStatus.WITHIN_FCL, valuation.underwritingStatus());
        assertEquals(0, money("500000000.00").compareTo(valuation.coveredAmount()));
    }

    @Test
    void aNonPositiveBenefitIsRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> GroupBenefitCalculator.evaluate(BigDecimal.ZERO, money("100000000.00")));
        assertThrows(IllegalArgumentException.class,
            () -> GroupBenefitCalculator.evaluate(null, money("100000000.00")));
    }

    // ---- coveredAfterDecision ------------------------------------------------

    @Test
    void acceptingTheExcessGrantsTheFullBenefit() {
        BigDecimal covered = GroupBenefitCalculator.coveredAfterDecision(
            money("150000000.00"), money("100000000.00"), true);
        assertEquals(0, money("150000000.00").compareTo(covered));
    }

    /**
     * Declined does not mean uninsured. The member keeps what the scheme gives without
     * evidence -- which is why DECLINED and EVIDENCE_REQUIRED share a covered amount and
     * still have to be distinguishable states.
     */
    @Test
    void decliningTheExcessLeavesCoverAtTheLimit() {
        BigDecimal covered = GroupBenefitCalculator.coveredAfterDecision(
            money("150000000.00"), money("100000000.00"), false);
        assertEquals(0, money("100000000.00").compareTo(covered));
    }
}
