package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import tz.co.nlolo.lifeplatform.underwriting.api.AssessmentType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

// Mirrors api/openapi/openapi-underwriting.yaml's SubmitAssessmentRequest: required
// [assessmentType, findings]; riskScore is optional/nullable per the spec.
public record SubmitAssessmentRequest(
    @NotNull AssessmentType assessmentType,
    @NotBlank String findings,
    BigDecimal riskScore) {}
