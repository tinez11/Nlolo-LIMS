package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import tz.co.nlolo.lifeplatform.underwriting.domain.RiskProfile;
import tz.co.nlolo.lifeplatform.underwriting.domain.RulesEnginePort;
import tz.co.nlolo.lifeplatform.underwriting.domain.UnderwritingDecision;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * PLACEHOLDER underwriting decision algorithm, pending Actuarial/Underwriting sign-off
 * on real rules (Global Constraints: no Phase 0 doc specifies actual thresholds or a
 * .drl rule set anywhere). Implements the RulesEnginePort abstraction so a real Drools
 * engine can be substituted later with zero change to callers -- Underwriting owns the
 * rule OUTCOMES (this class's return contract), not the engine mechanism itself
 * (docs/01-domain-map.md §2.2).
 *
 * Combined multiplier = ageBandMultiplier x sumAssuredBandMultiplier (occupation-class
 * and smoker-status factors are not yet consulted -- see RiskProfile's javadoc).
 * Decision thresholds below are placeholder values chosen to be explainable and
 * testable, not actuarially validated:
 *   - any submitted risk score >= 90                          -> POSTPONED (inconclusive, needs senior/medical review)
 *   - combinedMultiplier > 2.5 OR max risk score >= 75         -> DECLINED
 *   - max risk score >= 40                                     -> LOADED (loading% derived below)
 *   - otherwise                                                -> ACCEPT
 *
 * <p><b>RATING AND LOADING ARE DIFFERENT THINGS, and conflating them was the defect this
 * class carried.</b> The rating multiplier is what the product's own table says an applicant
 * of this age, buying this much cover, costs to insure -- it is the standard price for them,
 * not a penalty. A loading is the extra charge for what the EVIDENCE turned up about this
 * individual: an adverse medical, a hazardous occupation.
 *
 * <p>This engine used to express the rating multiplier as a loading -- {@code loadingPercent =
 * (combinedMultiplier - 1) x 100} -- and that was the only route by which the rating table
 * affected anything downstream, because the multiplier itself was returned to nobody. Two
 * consequences. A perfectly healthy 55-year-old was recorded as a LOADED life, as though
 * something were wrong with them, when the honest statement is that older cover costs more.
 * And on an ACCEPT the rating vanished entirely: a 25-year-old and a 55-year-old with the same
 * sum assured were charged the same premium, because the only carrier of the multiplier was a
 * loading that an ACCEPT does not have.
 *
 * <p>So the multiplier is now returned on the decision, applied to the premium at issuance, and
 * NO LONGER a loading. Age and sum assured move the price; they no longer move the outcome.
 * A rating above the decline threshold still declines -- an applicant the table prices at more
 * than 2.5x standard is outside what this product will write at any price.
 */
@Component
public class SimpleRulesEngine implements RulesEnginePort {

    private static final BigDecimal POSTPONE_RISK_SCORE_THRESHOLD = new BigDecimal("90");
    private static final BigDecimal DECLINE_MULTIPLIER_THRESHOLD = new BigDecimal("2.5");
    private static final BigDecimal DECLINE_RISK_SCORE_THRESHOLD = new BigDecimal("75");
    private static final BigDecimal LOAD_RISK_SCORE_THRESHOLD = new BigDecimal("40");
    private static final BigDecimal MINIMUM_LOADING_PERCENT = new BigDecimal("5");

    /**
     * 4dp because that is the scale {@code product.rating_table.multiplier} and
     * {@code underwriting_case.rating_multiplier} both store. Rounded ONCE, here, before the
     * thresholds are compared against it, so the number a decision was reached with, the number
     * persisted, and the number the premium is computed from are the same number rather than
     * three roundings of one.
     */
    private static final int MULTIPLIER_SCALE = 4;

    // PLACEHOLDER decision algorithm (see class javadoc): thresholds and loading
    // formula below are illustrative, not actuarially validated. Swap this method's
    // body -- not its signature -- when real Drools/DRL rules are formalized.
    @Override
    public UnderwritingDecision evaluate(RiskProfile riskProfile) {
        BigDecimal combinedMultiplier = riskProfile.ageBandMultiplier()
            .multiply(riskProfile.sumAssuredBandMultiplier())
            .setScale(MULTIPLIER_SCALE, RoundingMode.HALF_UP);
        BigDecimal maxRiskScore = riskProfile.assessmentRiskScores().stream()
            .max(BigDecimal::compareTo)
            .orElse(BigDecimal.ZERO);

        if (maxRiskScore.compareTo(POSTPONE_RISK_SCORE_THRESHOLD) >= 0) {
            return new UnderwritingDecision(UnderwritingDecision.Outcome.POSTPONED, null,
                "Risk assessment score " + maxRiskScore + " requires further medical evidence before a decision can be made",
                combinedMultiplier);
        }
        if (combinedMultiplier.compareTo(DECLINE_MULTIPLIER_THRESHOLD) > 0 || maxRiskScore.compareTo(DECLINE_RISK_SCORE_THRESHOLD) >= 0) {
            return new UnderwritingDecision(UnderwritingDecision.Outcome.DECLINED, null,
                "Combined rating multiplier " + combinedMultiplier + " or risk score " + maxRiskScore + " exceeds acceptable risk threshold",
                combinedMultiplier);
        }
        if (maxRiskScore.compareTo(LOAD_RISK_SCORE_THRESHOLD) >= 0) {
            // One percent of premium per risk-score point above the load threshold, never less
            // than 5%. Placeholder arithmetic like every threshold above it, but a 1:1 mapping
            // rather than a coefficient somebody would have to defend: at 40 the loading is the
            // 5% floor, at 74 (one point below a decline) it is 34%.
            //
            // The score drove nothing but the outcome before -- the AMOUNT came entirely from the
            // rating multiplier, so two applicants with wildly different medical findings were
            // loaded identically as long as their age and cover matched. Now the evidence sets
            // the loading and the rating table sets the price it loads.
            BigDecimal loadingPercent = maxRiskScore.subtract(LOAD_RISK_SCORE_THRESHOLD)
                .max(MINIMUM_LOADING_PERCENT).setScale(2, RoundingMode.HALF_UP);
            return new UnderwritingDecision(UnderwritingDecision.Outcome.LOADED, loadingPercent,
                "Elevated risk profile (max risk score " + maxRiskScore + ", rated at " + combinedMultiplier
                    + "x) accepted with premium loading",
                combinedMultiplier);
        }
        return new UnderwritingDecision(UnderwritingDecision.Outcome.ACCEPT, null,
            "Standard risk profile, rated at " + combinedMultiplier + "x", combinedMultiplier);
    }
}
