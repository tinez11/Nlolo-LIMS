package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ProductApi {

    /**
     * One rating multiplier.
     *
     * <p>{@code ageFrom}/{@code ageTo} are REQUIRED for {@link FactorType#AGE} and must be
     * null for every other type — {@code rating_table_age_bounds_shape} enforces the same
     * rule at the database. {@code ageTo} is inclusive.
     *
     * <p>They exist because age was previously matched by exact string equality against
     * {@code band}, and underwriting had no way to produce a matching string, so age was
     * never rated at all. {@code band} survives as the human-readable label; the bounds
     * are what the platform resolves on. The two-argument shape below keeps the ~45
     * existing non-AGE call sites unchanged.
     */
    record RatingFactorInput(FactorType factorType, String band, BigDecimal multiplier,
                              Integer ageFrom, Integer ageTo) {
        public RatingFactorInput(FactorType factorType, String band, BigDecimal multiplier) {
            this(factorType, band, multiplier, null, null);
        }
    }
    record BenefitInput(BenefitType benefitType, String calculationMethod) {}
    record FundInput(String fundCode, BigDecimal currentNav) {}

    /**
     * One cell of the base rate table: the annual rate per 1,000 of sum assured for
     * a given (age band, sex, smoker status). The base a premium is computed FROM;
     * {@link RatingFactorInput}'s multipliers apply on top for occupation class and
     * sum-assured band. {@code ageBand} shares its vocabulary with
     * {@code RatingFactorInput.band}.
     */
    /**
     * One cell of the base rate table: the annual rate per 1,000 of sum assured for
     * a given (age range, sex, smoker status). {@code ageTo} is INCLUSIVE. The base
     * a premium is computed FROM; {@link RatingFactorInput}'s multipliers apply on
     * top for occupation class and sum-assured band.
     */
    record BaseRateInput(int ageFrom, int ageTo, Sex sex, SmokerStatus smokerStatus, BigDecimal ratePerMille) {}

    /**
     * What a premium is quoted for. Money arrives as amount + currency rather than
     * a Money type, matching {@code PolicyApi}'s convention at this layer.
     *
     * Takes {@code dateOfBirth} and {@code asOf}, never a precomputed age: entry age
     * is the input a mispriced policy turns on, and it is derived where the rate
     * table lives. {@code occupationClass} and {@code smokerStatus} are ASSERTED by
     * the caller -- no party record carries either, which is recorded as an open item
     * in the M13 design spec.
     */
    record PremiumQuoteInput(UUID productId, BigDecimal sumAssuredAmount, String sumAssuredCurrency,
                             LocalDate dateOfBirth, Sex sex, SmokerStatus smokerStatus,
                             String occupationClass, String sumAssuredBand,
                             PremiumFrequency frequency, LocalDate asOf) {}

    /** One multiplier that was applied, in the order it was applied. */
    record AppliedFactor(FactorType factorType, String band, BigDecimal multiplier) {}

    /**
     * A quoted premium AND its derivation.
     *
     * The breakdown is returned, not just the total, because "reproduce exactly what
     * the customer was shown on a given date" is a stated requirement -- and without
     * it the only way for a UI to show one is to recompute it, which is the
     * client-side arithmetic `frontend/PLAN.md` §4 forbids and the mechanism by
     * which an illustration and its first invoice come to disagree.
     *
     * No policy fee is included: no product field holds one, and inventing a
     * constant would be a number with nothing behind it. Recorded as an open item.
     */
    record PremiumQuoteView(UUID productVersionId, String currency,
                            int ageAtEntry, int ageFrom, int ageTo, BigDecimal ratePerMille,
                            BigDecimal annualBase, List<AppliedFactor> appliedFactors,
                            BigDecimal annualAfterFactors,
                            PremiumFrequency frequency, int instalmentsPerYear,
                            BigDecimal instalmentAmount) {}

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
     * Price a product for one applicant, on the version active as of
     * {@code input.asOf()}.
     *
     * Resolution is STRICT throughout: a missing base rate table, an age outside
     * every band, or a declared occupation class / sum-assured band with no
     * multiplier each raise {@link PremiumNotQuotableException} naming the
     * dimension. Nothing falls back to a neutral value, because a fallback here
     * produces a plausible premium for a real contract and nothing fails.
     *
     * Deliberately NOT idempotency-keyed and it persists nothing: this is a
     * calculation, so replaying it is free and there is no duplicate to prevent.
     */
    PremiumQuoteView quotePremium(PremiumQuoteInput input);

    /**
     * The rating basis for one version, for actuarial and product review.
     *
     * Reuses the `*Input` records as the read shape: they already carry exactly
     * these fields, and a parallel set of output records would be two definitions
     * of one thing, free to drift. Precedent is `UnderwritingCaseView`, which is
     * likewise both the internal view and the wire DTO.
     *
     * Deliberately NOT folded into `ProductSnapshotView`, which Underwriting and
     * Billing read on the issuance and billing path.
     */
    record VersionRatingView(UUID productId, UUID productVersionId, LocalDate effectiveDate,
                             List<BaseRateInput> baseRates, List<RatingFactorInput> ratingFactors,
                             List<BenefitInput> benefitSchedule) {}

    VersionRatingView getVersionRating(UUID productId, UUID versionId);

    /**
     * Internal-only (not part of openapi-product.yaml), consumed by underwriting's
     * rules engine (Task 4/5). Returns 1.0 (neutral, no adjustment) if no rating-table
     * row matches the given band for that factor type on that product version --
     * callers must not treat a missing band as an error, since not every product
     * defines every factor type/band combination.
     */
    BigDecimal resolveRatingMultiplier(UUID productVersionId, FactorType factorType, String band);

    /**
     * The AGE multiplier for an applicant of {@code age}, resolved by RANGE rather than by
     * matching a band string.
     *
     * <p>Separate from {@link #resolveRatingMultiplier} because age is the one factor with
     * a natural ordering: every other type is a label the caller already holds
     * ({@code "CLASS_1"}, {@code "SMOKER"}), while age is a number that has to be placed
     * into whichever range covers it. Asking the caller to render an age as a band string
     * first is what left this factor unusable — underwriting could not produce a string
     * that matched, so it passed a sentinel and every applicant got 1.0.
     *
     * <p>Returns 1.0 when no AGE row covers the age, the same neutral-on-no-match contract
     * as {@code resolveRatingMultiplier}. That deliberately covers the versions published
     * before bounds existed: their AGE rows carry no range, match nobody, and so keep
     * behaving exactly as they always have rather than changing decisions retroactively.
     */
    BigDecimal resolveAgeMultiplier(UUID productVersionId, int age);

    /**
     * M3 addition: manual policy issuance (openapi-policy.yaml's ManualIssueRequest) supplies
     * only productVersionId, never a bare productId -- PolicyApi.IssueRequest needs both.
     * Internal-only, not part of openapi-product.yaml (same convention as
     * resolveRatingMultiplier).
     */
    ProductSnapshotView getSnapshotByVersionId(UUID productVersionId);
}
