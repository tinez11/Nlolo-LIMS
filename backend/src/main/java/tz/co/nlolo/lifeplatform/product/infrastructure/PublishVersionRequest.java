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
    /**
     * What this version covers. REQUIRED and non-empty -- see {@code publishVersion}. `@Valid`
     * for the reason given on {@code baseRates} below: without it the constraints on
     * BenefitRequest are never evaluated, and a benefit with no calculation method reaches the
     * service as a null.
     */
    @NotNull @Valid List<BenefitRequest> benefitSchedule,
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
    @Valid FrequencyLoadingRequest frequencyLoading,

    /**
     * The TIRA filing that authorises this version. REQUIRED -- in Tanzania a product and its
     * rates must be filed with and approved by TIRA before sale, and a version may not exist
     * without the filing that authorises it. There is deliberately no default.
     */
    @NotNull @Valid TiraFilingRequest tiraFiling,

    // Optional: present only on a savings version (product step 1). Absent means no cash value.
    @Valid CashValueRequest cashValue,

    // What the version pays while the life assured is alive (product step 2). Both optional, and
    // an absent block is still AUTHORED when it arrives over HTTP -- so an individual product
    // published with no free-look period is refused here rather than discovered in production.
    PayoutTermsRequest payoutTerms,

    @Valid List<PayoutRowRequest> payoutSchedule,

    // Present only on an ACCOUNT version (product step 3). Absent means SCALE -- what every version
    // published before this step is.
    @Valid AccumulationRequest accumulation,

    // Present only on a fixed-term deposit (2026-10-02). The server builds the account plan behind
    // it, so a request carrying both blocks is refused.
    @Valid DepositRequest deposit,

    // Present only on a with-profits version (product step 4). Absent means non-participating.
    @Valid BonusRequest bonus,

    // Present only on an ANNUITY version (product step 5), where it is required.
    @Valid AnnuityRequest annuity,

    // Present only on a FUNERAL version (family funeral cover), where it is required.
    @Valid FuneralRequest funeral) {}
