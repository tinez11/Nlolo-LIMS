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
 *   - combinedMultiplier > 1.0 OR max risk score >= 40         -> LOADED (loading% derived below)
 *   - otherwise                                                -> ACCEPT
 */
@Component
public class SimpleRulesEngine implements RulesEnginePort {

    private static final BigDecimal POSTPONE_RISK_SCORE_THRESHOLD = new BigDecimal("90");
    private static final BigDecimal DECLINE_MULTIPLIER_THRESHOLD = new BigDecimal("2.5");
    private static final BigDecimal DECLINE_RISK_SCORE_THRESHOLD = new BigDecimal("75");
    private static final BigDecimal LOAD_RISK_SCORE_THRESHOLD = new BigDecimal("40");

    // PLACEHOLDER decision algorithm (see class javadoc): thresholds and loading
    // formula below are illustrative, not actuarially validated. Swap this method's
    // body -- not its signature -- when real Drools/DRL rules are formalized.
    @Override
    public UnderwritingDecision evaluate(RiskProfile riskProfile) {
        BigDecimal combinedMultiplier = riskProfile.ageBandMultiplier().multiply(riskProfile.sumAssuredBandMultiplier());
        BigDecimal maxRiskScore = riskProfile.assessmentRiskScores().stream()
            .max(BigDecimal::compareTo)
            .orElse(BigDecimal.ZERO);

        if (maxRiskScore.compareTo(POSTPONE_RISK_SCORE_THRESHOLD) >= 0) {
            return new UnderwritingDecision(UnderwritingDecision.Outcome.POSTPONED, null,
                "Risk assessment score " + maxRiskScore + " requires further medical evidence before a decision can be made");
        }
        if (combinedMultiplier.compareTo(DECLINE_MULTIPLIER_THRESHOLD) > 0 || maxRiskScore.compareTo(DECLINE_RISK_SCORE_THRESHOLD) >= 0) {
            return new UnderwritingDecision(UnderwritingDecision.Outcome.DECLINED, null,
                "Combined rating multiplier " + combinedMultiplier + " or risk score " + maxRiskScore + " exceeds acceptable risk threshold");
        }
        if (combinedMultiplier.compareTo(BigDecimal.ONE) > 0 || maxRiskScore.compareTo(LOAD_RISK_SCORE_THRESHOLD) >= 0) {
            BigDecimal loadingFromMultiplier = combinedMultiplier.subtract(BigDecimal.ONE).multiply(new BigDecimal("100"));
            BigDecimal loadingPercent = loadingFromMultiplier.max(new BigDecimal("5")).setScale(2, RoundingMode.HALF_UP);
            return new UnderwritingDecision(UnderwritingDecision.Outcome.LOADED, loadingPercent,
                "Elevated risk profile (multiplier " + combinedMultiplier + ", max risk score " + maxRiskScore + ") accepted with premium loading");
        }
        return new UnderwritingDecision(UnderwritingDecision.Outcome.ACCEPT, null, "Standard risk profile");
    }
}
