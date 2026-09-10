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
    void elevatedMultiplierIsPricedNotLoaded() {
        // A 1.3x rating with clean evidence is a STANDARD life who costs more to insure -- an
        // older applicant, or a larger sum assured. It used to come back LOADED at 30%, which
        // said something untrue about the applicant AND was the only route by which the rating
        // reached a premium at all. The rating now travels on the decision itself.
        RiskProfile profile = new RiskProfile(new BigDecimal("1.3"), BigDecimal.ONE, List.of());
        UnderwritingDecision decision = engine.evaluate(profile);
        assertEquals(UnderwritingDecision.Outcome.ACCEPT, decision.outcome());
        assertNull(decision.loadingPercent());
        assertEquals(0, new BigDecimal("1.3").compareTo(decision.ratingMultiplier()));
    }

    @Test
    void ratingMultiplierIsTheProductOfBothFactorsAndIsReturnedOnEveryOutcome() {
        // The whole point of the field: it has to survive the call, on every path, or the
        // premium formula downstream has nothing to multiply by.
        assertEquals(0, new BigDecimal("1.76").compareTo(
            engine.evaluate(new RiskProfile(new BigDecimal("1.6"), new BigDecimal("1.1"), List.of()))
                .ratingMultiplier()));
        assertEquals(0, new BigDecimal("3.0").compareTo(
            engine.evaluate(new RiskProfile(new BigDecimal("2.0"), new BigDecimal("1.5"), List.of()))
                .ratingMultiplier()),
            "a DECLINED case was still rated, and the rating is what explains the decline");
        assertEquals(0, BigDecimal.ONE.compareTo(
            engine.evaluate(new RiskProfile(BigDecimal.ONE, BigDecimal.ONE, List.of(new BigDecimal("95"))))
                .ratingMultiplier()),
            "POSTPONED carries it too");
        assertEquals(0, new BigDecimal("1.2").compareTo(
            engine.evaluate(new RiskProfile(new BigDecimal("1.2"), BigDecimal.ONE, List.of(new BigDecimal("60"))))
                .ratingMultiplier()),
            "LOADED carries it too -- loading and rating are separate terms, not alternatives");
    }

    @Test
    void ratingMultiplierIsRoundedToTheScaleTheRatingTableStores() {
        // product.rating_table.multiplier and underwriting_case.rating_multiplier are both
        // NUMERIC(9,4). Rounding here, once, is what makes the number the decision was reached
        // with the same number that is persisted and later priced from.
        UnderwritingDecision decision = engine.evaluate(
            new RiskProfile(new BigDecimal("1.11111"), new BigDecimal("1.11111"), List.of()));
        assertEquals(4, decision.ratingMultiplier().scale());
        assertEquals(0, new BigDecimal("1.2346").compareTo(decision.ratingMultiplier()));
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
    void loadingIsDrivenByTheRiskScoreAndNotByTheRating() {
        // One percent per score point above the 40 load threshold. Two applicants with the same
        // evidence get the same loading whatever the rating table says about their age -- which
        // is the separation the old formula did not have, since the loading WAS the rating.
        UnderwritingDecision cheapToRate = engine.evaluate(
            new RiskProfile(BigDecimal.ONE, BigDecimal.ONE, List.of(new BigDecimal("60"))));
        UnderwritingDecision dearToRate = engine.evaluate(
            new RiskProfile(new BigDecimal("2.0"), BigDecimal.ONE, List.of(new BigDecimal("60"))));

        assertEquals(UnderwritingDecision.Outcome.LOADED, cheapToRate.outcome());
        assertEquals(UnderwritingDecision.Outcome.LOADED, dearToRate.outcome());
        assertEquals(0, new BigDecimal("20.00").compareTo(cheapToRate.loadingPercent()));
        assertEquals(0, new BigDecimal("20.00").compareTo(dearToRate.loadingPercent()));
        assertEquals(0, new BigDecimal("2.0").compareTo(dearToRate.ratingMultiplier()));
    }

    @Test
    void loadingNeverFallsBelowFivePercentAtTheLoadThreshold() {
        // A score of exactly 40 is zero points above the threshold, and a 0% loading on a LOADED
        // decision is a contradiction the DB CHECK would happily store.
        UnderwritingDecision decision = engine.evaluate(
            new RiskProfile(BigDecimal.ONE, BigDecimal.ONE, List.of(new BigDecimal("40"))));
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
    void combinedMultiplierOfExactlyTwoPointFiveIsAcceptedAtThatPrice() {
        // Boundary check: the decline rule is "> 2.5" (strictly greater than), so an exact 2.5 must
        // NOT decline. It is now ACCEPTED and rated at 2.5x rather than LOADED at 150% -- the same
        // money, said honestly: this product will write the applicant, at two and a half times the
        // standard rate. No risk scores are supplied, so the score paths cannot interfere.
        RiskProfile profile = new RiskProfile(new BigDecimal("2.5"), BigDecimal.ONE, List.of());
        UnderwritingDecision decision = engine.evaluate(profile);
        assertEquals(UnderwritingDecision.Outcome.ACCEPT, decision.outcome());
        assertNull(decision.loadingPercent());
        assertEquals(0, new BigDecimal("2.5").compareTo(decision.ratingMultiplier()));
    }

    @Test
    void aMultiplierBelowOneIsCarriedThroughAsADiscount() {
        // 0.5 x 2.0 = 1.0, so this is ACCEPT either way -- but the factors are real and a product
        // is free to publish a multiplier under 1.0 for a preferred band. Nothing in the engine
        // floors it at 1.0, and nothing downstream may either.
        RiskProfile profile = new RiskProfile(new BigDecimal("0.5"), new BigDecimal("1.2"), List.of());
        UnderwritingDecision decision = engine.evaluate(profile);
        assertEquals(UnderwritingDecision.Outcome.ACCEPT, decision.outcome());
        assertNull(decision.loadingPercent());
        assertEquals(0, new BigDecimal("0.6").compareTo(decision.ratingMultiplier()));
    }

    @Test
    void reasonStringIsPopulatedForEveryOutcome() {
        for (RiskProfile profile : List.of(
                new RiskProfile(BigDecimal.ONE, BigDecimal.ONE, List.of()),
                new RiskProfile(BigDecimal.ONE, BigDecimal.ONE, List.of(new BigDecimal("55"))),
                new RiskProfile(new BigDecimal("3.0"), BigDecimal.ONE, List.of()),
                new RiskProfile(BigDecimal.ONE, BigDecimal.ONE, List.of(new BigDecimal("95"))))) {
            assertNotNull(engine.evaluate(profile).reason());
        }
    }
}
