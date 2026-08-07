package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.api.IfrsMeasurementModel;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

// Mirrors api/openapi/openapi-product.yaml's ProductVersionSpec: required
// [ifrsMeasurementModel, effectiveDate, ratingTable, benefitSchedule]; retirementDate and
// fundDefinitions are optional/nullable per the spec.
public record PublishVersionRequest(
    @NotNull IfrsMeasurementModel ifrsMeasurementModel,
    @NotNull LocalDate effectiveDate,
    LocalDate retirementDate,
    @NotNull List<RatingFactorRequest> ratingTable,
    @NotNull List<BenefitRequest> benefitSchedule,
    List<FundDefinitionRequest> fundDefinitions) {}
