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
