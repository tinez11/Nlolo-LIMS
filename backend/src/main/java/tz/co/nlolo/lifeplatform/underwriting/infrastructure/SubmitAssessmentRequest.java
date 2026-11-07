package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import tz.co.nlolo.lifeplatform.underwriting.api.AssessmentType;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

// Mirrors api/openapi/openapi-underwriting.yaml's SubmitAssessmentRequest: required
// [assessmentType, findings]; riskScore is optional/nullable per the spec.
public record SubmitAssessmentRequest(
    @NotNull AssessmentType assessmentType,
    @NotBlank String findings,
    // 0 to 100, the scale SimpleRulesEngine's bands are on (2026-11-07): 500 used to be recorded as evidence.
    @DecimalMin(value = "0", message = "A risk score is from 0 to 100")
    @DecimalMax(value = "100", message = "A risk score is from 0 to 100")
    BigDecimal riskScore) {}
