package tz.co.nlolo.lifeplatform.underwriting.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * Inputs to the underwriting decision. ageBandMultiplier and sumAssuredBandMultiplier
 * are resolved by the caller (UnderwritingApiImpl, Task 5) from product.ProductApi.
 * assessmentRiskScores are the case's submitted RiskAssessment.riskScore values
 * (0-100 scale, higher = more risk), empty if no assessments have been submitted yet.
 *
 * OCCUPATION_CLASS IS NOW PART OF THIS PROFILE, and the instruction that put it here was
 * written in this javadoc: it said the factor was left out because "no structured data
 * source exists yet", and that "a future milestone that adds structured
 * medical/occupational intake fields should extend this record and SimpleRulesEngine
 * together, not silently ignore the new data by leaving it out of the profile passed in."
 * The person record (party V2) added {@code occupationClass}, the registration form
 * collects it, and the client screen displays it -- so the condition was met and the
 * factor was being silently ignored exactly as warned. A real product published an
 * OCCUPATION_CLASS multiplier of 2.5 that reached no premium at all.
 *
 * SMOKER_STATUS remains out, and for a different reason rather than the same one: it is a
 * key of base_rate_table, so a rating multiplier for it would be counted twice -- which is
 * why publishVersion refuses one on a priced version.
 */
public record RiskProfile(BigDecimal ageBandMultiplier, BigDecimal sumAssuredBandMultiplier,
                           BigDecimal occupationClassMultiplier, List<BigDecimal> assessmentRiskScores) {

    /**
     * Pre-occupation shape, kept so the engine's own unit tests -- which are about thresholds
     * and say nothing about occupation -- do not each have to state a neutral one.
     */
    public RiskProfile(BigDecimal ageBandMultiplier, BigDecimal sumAssuredBandMultiplier,
                        List<BigDecimal> assessmentRiskScores) {
        this(ageBandMultiplier, sumAssuredBandMultiplier, BigDecimal.ONE, assessmentRiskScores);
    }
}
