package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ProductApi {

    record RatingFactorInput(FactorType factorType, String band, BigDecimal multiplier) {}
    record BenefitInput(BenefitType benefitType, String calculationMethod) {}
    record FundInput(String fundCode, BigDecimal currentNav) {}

    /**
     * One cell of the base rate table: the annual rate per 1,000 of sum assured for
     * a given (age band, sex, smoker status). The base a premium is computed FROM;
     * {@link RatingFactorInput}'s multipliers apply on top for occupation class and
     * sum-assured band. {@code ageBand} shares its vocabulary with
     * {@code RatingFactorInput.band}.
     */
    record BaseRateInput(String ageBand, Sex sex, SmokerStatus smokerStatus, BigDecimal ratePerMille) {}

    ProductSummaryView createProduct(String productCode, String productName, ProductCategory category, String defaultCurrency, String createdBy);

    List<ProductSummaryView> listActiveProducts(ProductCategory categoryFilter);

    /**
     * Publish a version with no base rate table. Such a version is valid and
     * sellable but **cannot be priced**: a premium quote against it fails with a
     * clear error rather than guessing.
     *
     * Base rates are deliberately NOT mandatory at publish, contrary to the M13
     * design spec's first draft. Two reasons, both found while implementing:
     * GROUP_LIFE is rated on scheme size, so it cannot populate an
     * (age band, sex, smoker) key at all and would become unpublishable; and 45
     * existing call sites publish perfectly valid versions that predate pricing.
     * An unpriceable version is therefore a real state, surfaced where it matters
     * — at quote time — rather than made unrepresentable at the cost of blocking
     * a product line.
     */
    default void publishVersion(UUID productId, IfrsMeasurementModel ifrsMeasurementModel, LocalDate effectiveDate, LocalDate retirementDate,
                         List<RatingFactorInput> ratingTable, List<BenefitInput> benefitSchedule, List<FundInput> fundDefinitions, String publishedBy) {
        publishVersion(productId, ifrsMeasurementModel, effectiveDate, retirementDate, ratingTable, benefitSchedule,
            fundDefinitions, List.of(), publishedBy);
    }

    /**
     * Publish a version, optionally with the base rate table it is priced from.
     *
     * If {@code baseRates} is non-empty, {@code ratingTable} must NOT also carry
     * AGE or SMOKER_STATUS factors — those dimensions are already keys of the base
     * rate table, and applying them in both places would double-count them into
     * the premium. Rejected here with {@link InvalidProductVersionException},
     * because it is the single most likely defect in this design and it is
     * cheapest to make unrepresentable.
     */
    void publishVersion(UUID productId, IfrsMeasurementModel ifrsMeasurementModel, LocalDate effectiveDate, LocalDate retirementDate,
                         List<RatingFactorInput> ratingTable, List<BenefitInput> benefitSchedule, List<FundInput> fundDefinitions,
                         List<BaseRateInput> baseRates, String publishedBy);

    ProductSnapshotView getActiveSnapshot(UUID productId, LocalDate asOfDate);

    /**
     * Internal-only (not part of openapi-product.yaml), consumed by underwriting's
     * rules engine (Task 4/5). Returns 1.0 (neutral, no adjustment) if no rating-table
     * row matches the given band for that factor type on that product version --
     * callers must not treat a missing band as an error, since not every product
     * defines every factor type/band combination.
     */
    BigDecimal resolveRatingMultiplier(UUID productVersionId, FactorType factorType, String band);

    /**
     * M3 addition: manual policy issuance (openapi-policy.yaml's ManualIssueRequest) supplies
     * only productVersionId, never a bare productId -- PolicyApi.IssueRequest needs both.
     * Internal-only, not part of openapi-product.yaml (same convention as
     * resolveRatingMultiplier).
     */
    ProductSnapshotView getSnapshotByVersionId(UUID productVersionId);
}
