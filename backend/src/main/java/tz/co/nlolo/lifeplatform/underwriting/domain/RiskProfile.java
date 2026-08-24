package tz.co.nlolo.lifeplatform.underwriting.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * Inputs to the underwriting decision. ageBandMultiplier and sumAssuredBandMultiplier
 * are resolved by the caller (UnderwritingApiImpl, Task 5) from product.ProductApi.
 * assessmentRiskScores are the case's submitted RiskAssessment.riskScore values
 * (0-100 scale, higher = more risk), empty if no assessments have been submitted yet.
 *
 * OCCUPATION_CLASS and SMOKER_STATUS rating factors are deliberately NOT part of this
 * profile -- no structured data source exists yet for either (see plan Global
 * Constraints). A future milestone that adds structured medical/occupational intake
 * fields should extend this record and SimpleRulesEngine together, not silently ignore
 * the new data by leaving it out of the profile passed in.
 */
public record RiskProfile(BigDecimal ageBandMultiplier, BigDecimal sumAssuredBandMultiplier, List<BigDecimal> assessmentRiskScores) {}
