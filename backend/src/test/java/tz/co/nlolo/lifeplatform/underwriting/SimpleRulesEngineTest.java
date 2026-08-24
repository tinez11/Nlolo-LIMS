package tz.co.nlolo.lifeplatform.underwriting;

import tz.co.nlolo.lifeplatform.underwriting.domain.RiskProfile;
import tz.co.nlolo.lifeplatform.underwriting.domain.UnderwritingDecision;
import tz.co.nlolo.lifeplatform.underwriting.infrastructure.SimpleRulesEngine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SimpleRulesEngineTest {

    private final SimpleRulesEngine engine = new SimpleRulesEngine();

    @Test
    void standardRiskProfileIsAccepted() {
        RiskProfile profile = new RiskProfile(BigDecimal.ONE, BigDecimal.ONE, List.of());
        UnderwritingDecision decision = engine.evaluate(profile);
        assertEquals(UnderwritingDecision.Outcome.ACCEPT, decision.outcome());
        assertNull(decision.loadingPercent());
    }

    @Test
    void moderatelyElevatedMultiplierIsLoadedNotDeclined() {
        RiskProfile profile = new RiskProfile(new BigDecimal("1.3"), BigDecimal.ONE, List.of());
        UnderwritingDecision decision = engine.evaluate(profile);
        assertEquals(UnderwritingDecision.Outcome.LOADED, decision.outcome());
        assertEquals(0, new BigDecimal("30.00").compareTo(decision.loadingPercent()));
    }

    @Test
    void highMultiplierIsDeclined() {
        RiskProfile profile = new RiskProfile(new BigDecimal("2.0"), new BigDecimal("1.5"), List.of());
        UnderwritingDecision decision = engine.evaluate(profile);
        assertEquals(UnderwritingDecision.Outcome.DECLINED, decision.outcome());
        assertNull(decision.loadingPercent());
    }

    @Test
    void veryHighRiskScoreIsPostponedRegardlessOfMultiplier() {
        RiskProfile profile = new RiskProfile(BigDecimal.ONE, BigDecimal.ONE, List.of(new BigDecimal("95")));
        UnderwritingDecision decision = engine.evaluate(profile);
        assertEquals(UnderwritingDecision.Outcome.POSTPONED, decision.outcome());
    }

    @Test
    void highRiskScoreAloneDeclinesEvenWithNeutralMultiplier() {
        RiskProfile profile = new RiskProfile(BigDecimal.ONE, BigDecimal.ONE, List.of(new BigDecimal("80")));
        UnderwritingDecision decision = engine.evaluate(profile);
        assertEquals(UnderwritingDecision.Outcome.DECLINED, decision.outcome());
    }

    @Test
    void moderateRiskScoreAloneLoadsWithMinimumFivePercent() {
        RiskProfile profile = new RiskProfile(BigDecimal.ONE, BigDecimal.ONE, List.of(new BigDecimal("50")));
        UnderwritingDecision decision = engine.evaluate(profile);
        assertEquals(UnderwritingDecision.Outcome.LOADED, decision.outcome());
        assertEquals(0, new BigDecimal("5.00").compareTo(decision.loadingPercent()));
    }

    @Test
    void multipleAssessmentsUseTheHighestRiskScore() {
        RiskProfile profile = new RiskProfile(BigDecimal.ONE, BigDecimal.ONE, List.of(new BigDecimal("20"), new BigDecimal("92"), new BigDecimal("55")));
        UnderwritingDecision decision = engine.evaluate(profile);
        assertEquals(UnderwritingDecision.Outcome.POSTPONED, decision.outcome());
    }

    @Test
    void postponeThresholdTakesPrecedenceOverDeclineThreshold() {
        // A very high risk score (>=90) postpones even when the multiplier alone would decline --
        // postponement (need more evidence) outranks an automatic decline in this placeholder algorithm.
        RiskProfile profile = new RiskProfile(new BigDecimal("3.0"), BigDecimal.ONE, List.of(new BigDecimal("91")));
        UnderwritingDecision decision = engine.evaluate(profile);
        assertEquals(UnderwritingDecision.Outcome.POSTPONED, decision.outcome());
    }

    @Test
    void riskScoreOfExactlyNinetyIsPostponed() {
        // Boundary check: the brief's ">=90" threshold must include 90 itself, not just values above it.
        RiskProfile profile = new RiskProfile(BigDecimal.ONE, BigDecimal.ONE, List.of(new BigDecimal("90")));
        UnderwritingDecision decision = engine.evaluate(profile);
        assertEquals(UnderwritingDecision.Outcome.POSTPONED, decision.outcome());
    }

    @Test
    void riskScoreOfExactlySeventyFiveIsDeclined() {
        // Boundary check: the brief's ">=75" threshold must include 75 itself. Multiplier is neutral (1.0)
        // so it cannot trigger DECLINE on its own -- only the risk-score path can produce this outcome.
        RiskProfile profile = new RiskProfile(BigDecimal.ONE, BigDecimal.ONE, List.of(new BigDecimal("75")));
        UnderwritingDecision decision = engine.evaluate(profile);
        assertEquals(UnderwritingDecision.Outcome.DECLINED, decision.outcome());
    }

    @Test
    void combinedMultiplierOfExactlyTwoPointFiveIsNotDeclinedByMultiplierAlone() {
        // Boundary check: the brief's decline multiplier rule is "> 2.5" (strictly greater than), so an
        // exact 2.5 must NOT decline via the multiplier path. Confirmed against SimpleRulesEngine's
        // `combinedMultiplier.compareTo(DECLINE_MULTIPLIER_THRESHOLD) > 0` check. No risk scores are
        // supplied so the risk-score decline/postpone paths cannot interfere. At 2.5 the multiplier still
        // exceeds the LOAD threshold of 1.0, so the actual outcome is LOADED with a 150% loading.
        RiskProfile profile = new RiskProfile(new BigDecimal("2.5"), BigDecimal.ONE, List.of());
        UnderwritingDecision decision = engine.evaluate(profile);
        assertEquals(UnderwritingDecision.Outcome.LOADED, decision.outcome());
        assertEquals(0, new BigDecimal("150.00").compareTo(decision.loadingPercent()));
    }

    @Test
    void combinedMultiplierOfExactlyOneIsNotLoadedByMultiplierAlone() {
        // Boundary check: the brief's load multiplier rule is "> 1.0" (strictly greater than), so an exact
        // 1.0 must NOT load via the multiplier path. Confirmed against SimpleRulesEngine's
        // `combinedMultiplier.compareTo(BigDecimal.ONE) > 0` check. No risk scores are supplied so the
        // risk-score load threshold (>=40) cannot interfere, and the resulting outcome is ACCEPT.
        RiskProfile profile = new RiskProfile(new BigDecimal("0.5"), new BigDecimal("2.0"), List.of());
        UnderwritingDecision decision = engine.evaluate(profile);
        assertEquals(UnderwritingDecision.Outcome.ACCEPT, decision.outcome());
        assertNull(decision.loadingPercent());
    }

    @Test
    void reasonStringIsPopulatedForEveryOutcome() {
        for (RiskProfile profile : List.of(
                new RiskProfile(BigDecimal.ONE, BigDecimal.ONE, List.of()),
                new RiskProfile(new BigDecimal("1.3"), BigDecimal.ONE, List.of()),
                new RiskProfile(new BigDecimal("3.0"), BigDecimal.ONE, List.of()),
                new RiskProfile(BigDecimal.ONE, BigDecimal.ONE, List.of(new BigDecimal("95"))))) {
            assertNotNull(engine.evaluate(profile).reason());
        }
    }
}
