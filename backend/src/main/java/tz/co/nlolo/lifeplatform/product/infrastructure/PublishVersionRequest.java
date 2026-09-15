package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.api.IfrsMeasurementModel;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

// Mirrors api/openapi/openapi-product.yaml's ProductVersionSpec: required
// [ifrsMeasurementModel, effectiveDate, ratingTable, benefitSchedule]; retirementDate,
// fundDefinitions and baseRates are optional/nullable per the spec.
public record PublishVersionRequest(
    @NotNull IfrsMeasurementModel ifrsMeasurementModel,
    @NotNull LocalDate effectiveDate,
    LocalDate retirementDate,
    @NotNull List<RatingFactorRequest> ratingTable,
    @NotNull List<BenefitRequest> benefitSchedule,
    List<FundDefinitionRequest> fundDefinitions,
    /**
     * The rates this version is priced from. Optional: a version without them is
     * valid and sellable but cannot be quoted — GROUP_LIFE is rated on scheme
     * size and cannot populate an (age band, sex, smoker) key at all. `@Valid`
     * cascades validation into each cell; without it the constraints on
     * BaseRateRequest are never evaluated.
     */
    @Valid List<BaseRateRequest> baseRates,

    /**
     * What this version will accept. Optional -- an unbounded version is a real product
     * design. See {@link tz.co.nlolo.lifeplatform.product.api.EligibilityBounds} for why
     * entry age and term are hard refusals while sum assured is a soft flag.
     */
    @Valid EligibilityBoundsRequest eligibility,

    /**
     * What this version charges for instalment payment. Optional -- an unloaded version charges a
     * monthly payer the same total as an annual one, which is a real pricing decision and is what
     * every version published before this field existed does.
     */
    @Valid FrequencyLoadingRequest frequencyLoading) {}
