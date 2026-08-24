package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ProductApi {

    record RatingFactorInput(FactorType factorType, String band, BigDecimal multiplier) {}
    record BenefitInput(BenefitType benefitType, String calculationMethod) {}
    record FundInput(String fundCode, BigDecimal currentNav) {}

    ProductSummaryView createProduct(String productCode, String productName, ProductCategory category, String defaultCurrency, String createdBy);

    List<ProductSummaryView> listActiveProducts(ProductCategory categoryFilter);

    void publishVersion(UUID productId, IfrsMeasurementModel ifrsMeasurementModel, LocalDate effectiveDate, LocalDate retirementDate,
                         List<RatingFactorInput> ratingTable, List<BenefitInput> benefitSchedule, List<FundInput> fundDefinitions, String publishedBy);

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
