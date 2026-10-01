package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
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
                              Integer ageFrom, Integer ageTo,
                              /**
                               * Inclusive bounds for a SUM_ASSURED_BAND row (V9), and null for
                               * every other factor type. What the platform actually matches a
                               * sum assured against — {@code band} stays as the label an actuary
                               * reads, exactly as it does for AGE.
                               */
                              BigDecimal sumAssuredFrom, BigDecimal sumAssuredTo) {
        public RatingFactorInput(FactorType factorType, String band, BigDecimal multiplier) {
            this(factorType, band, multiplier, null, null, null, null);
        }

        public RatingFactorInput(FactorType factorType, String band, BigDecimal multiplier,
                                  Integer ageFrom, Integer ageTo) {
            this(factorType, band, multiplier, ageFrom, ageTo, null, null);
        }
    }
    /**
     * One benefit at authoring time.
     *
     * <p>{@code calculationMethod} is an ENUM, not a String, and there is deliberately no String
     * overload: a stringly-typed door here is how {@code calculation_method} came to hold
     * {@code untill death} on two of the three rows that existed.
     */
    record BenefitInput(BenefitType benefitType, BenefitCalculationMethod calculationMethod,
                        BigDecimal percent, BigDecimal flatAmount) {
        public BenefitInput(BenefitType benefitType, BenefitCalculationMethod calculationMethod) {
            this(benefitType, calculationMethod, null, null);
        }
    }
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
    /**
     * One base-rate cell. {@code termFromMonths}/{@code termToMonths} band the rate by policy term
     * (V16): both null = any term (the pre-term-banding shape), both set = a term within [from, to]
     * inclusive. The 5-arg form is kept so every existing unbanded call site reads unchanged.
     */
    record BaseRateInput(int ageFrom, int ageTo, Sex sex, SmokerStatus smokerStatus, BigDecimal ratePerMille,
                         Integer termFromMonths, Integer termToMonths) {
        public BaseRateInput(int ageFrom, int ageTo, Sex sex, SmokerStatus smokerStatus, BigDecimal ratePerMille) {
            this(ageFrom, ageTo, sex, smokerStatus, ratePerMille, null, null);
        }
    }

    /**
     * What a premium is quoted for. Money arrives as amount + currency rather than
     * a Money type, matching {@code PolicyApi}'s convention at this layer.
     *
     * Takes {@code dateOfBirth} and {@code asOf}, never a precomputed age: entry age
     * is the input a mispriced policy turns on, and it is derived where the rate
     * table lives.
     *
     * <p><b>There is deliberately no {@code sumAssuredBand}.</b> It used to be asserted by the
     * caller and matched by string equality against {@code rating_table.band} — V9's defect,
     * removed from the issuance path and left live here because this endpoint had no frontend
     * caller. A band string a caller invents cannot be validated against anything, and
     * {@code sumAssuredAmount} determines the band by definition, so asking for both only invited
     * them to disagree — and they did.
     *
     * <p>{@code occupationClass} and {@code smokerStatus} are still ASSERTED by the caller: no
     * party record is read on this path.
     */
    /**
     * {@code policyTermMonths} is the term the quote is for (V16). Optional: null means "no term"
     * (whole life, an annuity), which prices only against unbanded rates and is refused on a
     * term-banded version. The 8-arg form is kept so existing unbanded call sites read unchanged.
     */
    record PremiumQuoteInput(UUID productId, BigDecimal sumAssuredAmount, String sumAssuredCurrency,
                             LocalDate dateOfBirth, Sex sex, SmokerStatus smokerStatus,
                             String occupationClass,
                             PremiumFrequency frequency, LocalDate asOf, Integer policyTermMonths) {
        public PremiumQuoteInput(UUID productId, BigDecimal sumAssuredAmount, String sumAssuredCurrency,
                                 LocalDate dateOfBirth, Sex sex, SmokerStatus smokerStatus,
                                 String occupationClass, PremiumFrequency frequency, LocalDate asOf) {
            this(productId, sumAssuredAmount, sumAssuredCurrency, dateOfBirth, sex, smokerStatus,
                occupationClass, frequency, asOf, null);
        }
    }

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
                            /**
                             * What paying at this frequency costs, and the annual figure the
                             * instalment is actually divided from. Both present even when zero:
                             * without them the view reports an annual premium that does not divide
                             * into the instalment beside it, which is worse than showing nothing.
                             */
                            BigDecimal frequencyLoadingPercent, BigDecimal annualAfterFrequencyLoading,
                            PremiumFrequency frequency, int instalmentsPerYear,
                            BigDecimal instalmentAmount) {}

    ProductSummaryView createProduct(String productCode, String productName, ProductCategory category, String defaultCurrency, String createdBy);

    List<ProductSummaryView> listActiveProducts(ProductCategory categoryFilter);

    /**
     * Every DRAFT product for the tenant — a product created but never published.
     *
     * <p>Publishing a version is what flips DRAFT to ACTIVE, and {@link #listActiveProducts}
     * returns ACTIVE only, so before this existed a DRAFT was unreachable through the entire
     * API: absent from the catalogue, and {@link #getActiveSnapshot} throws for it too. It
     * still held its product code against {@code ux_product_code}, so abandoning the second
     * phase of authoring burned that code permanently and left nothing anybody could see,
     * finish or remove. Reported from the console as "already exists but is not on the list".
     *
     * <p>Deliberately NOT a {@code status} parameter on {@code listActiveProducts}: that
     * endpoint is open to {@code REALM_CUSTOMERS} and {@code REALM_AGENTS}, and a parameter
     * that widens what an open endpoint returns is one refactor away from letting a
     * policyholder enumerate products the insurer has not launched. A separate method carries
     * a separate {@code @PreAuthorize}, and the controller gates this one on ADMIN — the same
     * role that may author a product in the first place.
     */
    List<ProductSummaryView> listDraftProducts();

    /**
     * One product by its id — its code, name, category and status.
     *
     * <p>There was no way to turn a {@code productId} into a product NAME. {@link
     * #getActiveSnapshot} takes an id but returns pricing and eligibility, never the code or
     * the name; {@link #listActiveProducts} carries both but is keyed by nothing, so a caller
     * holding an id had to fetch the whole catalogue and scan it — and got nothing at all for
     * a product that is DRAFT or has been retired out of the catalogue.
     *
     * <p>So every screen that referenced a product it had not itself chosen from a list
     * printed the raw uuid: the underwriting queue rendered a column of them, and the case
     * detail rail printed one under the label "Product" with a note admitting no lookup
     * existed. An underwriter deciding a case could not see which product it was for.
     *
     * <p>Gated exactly like {@link #listActiveProducts} in the controller, and NOT like
     * {@link #listDraftProducts}: the reason drafts are ADMIN-only is that a list lets a
     * policyholder ENUMERATE products the insurer has not launched. Resolving one id that the
     * caller already holds — off their own policy, their own case — enumerates nothing.
     *
     * @throws ProductNotFoundException if no such product exists for this tenant
     */
    ProductSummaryView getProduct(UUID productId);

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
     *
     * <p><b>Abstract, not a {@code default} method, and that is load-bearing.</b> It was
     * a {@code default} delegating to the fuller overload, which meant Spring's
     * transaction proxy never applied: a {@code default} interface method carries no
     * annotation, so the proxy passed the call to the target and the delegating call
     * became a self-invocation that never re-entered the proxy. The whole publish then
     * ran OUTSIDE the {@code @Transactional} the implementation declares — and roughly
     * 48 of the 67 callers use this overload.
     *
     * <p>The consequence is a partial write, not a lost event: the method retires every
     * currently-ACTIVE version with {@code saveAndFlush} (deliberately immediate) before
     * inserting the new one. Two concurrent publishes for the same product both retire
     * the active version and one then loses on {@code ux_product_version_active}; with a
     * transaction the loser rolls back, without one its retirement is already committed
     * and the product is left with no active version at all. Same failure family as
     * Build 1 §9.1, where the mechanism was proven empirically.
     */
    void publishVersion(UUID productId, IfrsMeasurementModel ifrsMeasurementModel, LocalDate effectiveDate, LocalDate retirementDate,
                         List<RatingFactorInput> ratingTable, List<BenefitInput> benefitSchedule, List<FundInput> fundDefinitions, TiraFiling tiraFiling, String publishedBy);

    /**
     * Set how long this version's exclusion windows run, in months from cover start.
     *
     * <p>Its own operation rather than two more parameters on {@link #publishVersion}, which
     * has thirty-odd call sites with no opinion about exclusions. Null clears a window: a
     * product with no such exclusion is the normal case and must stay expressible.
     *
     * <p>Credit life carries 12 and 12, confirmed by the client on 2026-09-22. Below the free
     * cover limit nobody is underwritten — and with the limit far above any loan these lenders
     * write, nobody ever is — so these two windows are the entire anti-selection control the
     * product has.
     */
    void setExclusionPeriods(UUID productVersionId, Integer suicideMonths,
                              Integer preExistingMonths, String changedBy);

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
                         List<BaseRateInput> baseRates, TiraFiling tiraFiling, String publishedBy);

    /**
     * Publish a version that states what it will accept.
     *
     * <p>The fullest form; every other overload delegates here. {@code bounds} may be
     * {@link EligibilityBounds#none()} — an unbounded version is a real product design,
     * not an omission.
     */
    void publishVersion(UUID productId, IfrsMeasurementModel ifrsMeasurementModel, LocalDate effectiveDate, LocalDate retirementDate,
                         List<RatingFactorInput> ratingTable, List<BenefitInput> benefitSchedule, List<FundInput> fundDefinitions,
                         List<BaseRateInput> baseRates, EligibilityBounds bounds, TiraFiling tiraFiling, String publishedBy);

    /**
     * Publish a version that states what it will accept AND what instalment payment costs.
     *
     * <p>The fullest form; every other overload delegates here. {@code bounds} may be
     * {@link EligibilityBounds#none()} and {@code frequencyLoading} may be
     * {@link FrequencyLoading#none()} — an unbounded, unloaded version is a real product design.
     */
    void publishVersion(UUID productId, IfrsMeasurementModel ifrsMeasurementModel, LocalDate effectiveDate, LocalDate retirementDate,
                         List<RatingFactorInput> ratingTable, List<BenefitInput> benefitSchedule, List<FundInput> fundDefinitions,
                         List<BaseRateInput> baseRates, EligibilityBounds bounds, FrequencyLoading frequencyLoading, TiraFiling tiraFiling, String publishedBy);

    /**
     * Also the version's cash-value table and its actuarial sign-off (product step 1). The table is
     * written in the same transaction as the version, so a version can never exist half-valued.
     */
    void publishVersion(UUID productId, IfrsMeasurementModel ifrsMeasurementModel, LocalDate effectiveDate, LocalDate retirementDate,
                         List<RatingFactorInput> ratingTable, List<BenefitInput> benefitSchedule, List<FundInput> fundDefinitions,
                         List<BaseRateInput> baseRates, EligibilityBounds bounds, FrequencyLoading frequencyLoading,
                         TiraFiling tiraFiling, CashValuePlan cashValue, String publishedBy);

    /**
     * The fullest form: also what the version pays while the life assured is ALIVE (product step 2).
     * Every other overload delegates here with {@link PayoutPlan#none()}, which is exempt from the
     * authoring rules -- see {@link PayoutPlan} for why a fixture and a real product are held to
     * different standards.
     */
    void publishVersion(UUID productId, IfrsMeasurementModel ifrsMeasurementModel, LocalDate effectiveDate, LocalDate retirementDate,
                         List<RatingFactorInput> ratingTable, List<BenefitInput> benefitSchedule, List<FundInput> fundDefinitions,
                         List<BaseRateInput> baseRates, EligibilityBounds bounds, FrequencyLoading frequencyLoading,
                         TiraFiling tiraFiling, CashValuePlan cashValue, PayoutPlan payoutPlan, String publishedBy);

    /**
     * The fullest form (product step 3): also how the version is VALUED. Every other overload
     * delegates here with {@link AccumulationPlan#none()} -- a SCALE version, which is what every
     * version before this step is.
     */
    void publishVersion(UUID productId, IfrsMeasurementModel ifrsMeasurementModel, LocalDate effectiveDate, LocalDate retirementDate,
                         List<RatingFactorInput> ratingTable, List<BenefitInput> benefitSchedule, List<FundInput> fundDefinitions,
                         List<BaseRateInput> baseRates, EligibilityBounds bounds, FrequencyLoading frequencyLoading,
                         TiraFiling tiraFiling, CashValuePlan cashValue, PayoutPlan payoutPlan,
                         AccumulationPlan accumulationPlan, String publishedBy);

    /**
     * A version's account terms; {@link AccumulationPlan#none()} for a SCALE version. Internal-only,
     * like {@link #resolvePayoutPlan} -- consumed by {@code accumulation} and {@code policy}.
     */
    AccumulationPlan resolveAccumulationPlan(UUID productVersionId);

    /**
     * What this version pays while the life assured lives, and its free-look and proof-of-life
     * terms. {@link PayoutPlan#none()} for a version with neither.
     *
     * <p>Internal-only, not part of {@code openapi-product.yaml} -- the same convention as
     * {@link #resolveBenefitSchedule}. Consumed by {@code benefitpayout} at issuance to expand the
     * schedule, and by {@code policy} to know whether a policy matures or merely expires.
     */
    PayoutPlan resolvePayoutPlan(UUID productVersionId);

    /**
     * What this version charges for instalment payment.
     *
     * <p>Internal-only, not part of {@code openapi-product.yaml} — the same convention as
     * {@link #isPriced} and {@link #resolveBaseRatePerMille}. Consumed by policy's issuance
     * listener so an issued premium and a quoted one are loaded identically.
     *
     * @throws ProductNotFoundException if no such version exists for this tenant
     */
    FrequencyLoading resolveFrequencyLoading(UUID productVersionId);

    /**
     * What this version covers, and what each benefit pays.
     *
     * <p>Internal-only, not part of {@code openapi-product.yaml} — the same convention as
     * {@link #isPriced} and {@link #resolveFrequencyLoading}. Consumed by policy at issuance so a
     * contract's coverage is what its product actually authored, rather than a hardcoded
     * {@code DEATH} row.
     *
     * <p><b>Empty for the 143 versions published before an authored benefit was required.</b>
     * Callers must read empty as "this version predates the rule", never as "this product covers
     * nothing" — {@code publishVersion} now refuses an empty schedule, so that population can only
     * shrink.
     */
    List<BenefitDefinition> resolveBenefitSchedule(UUID productVersionId);

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
                             List<BenefitInput> benefitSchedule,
                             /**
                              * What paying in instalments costs. Part of the rating basis an
                              * actuary reviews, so it belongs on this read rather than only on a
                              * quote. {@link FrequencyLoading#none()} when nothing is loaded.
                              */
                             FrequencyLoading frequencyLoading,
                             /**
                              * The filing that authorises this version, or null for one published
                              * before V12. Part of what a reviewer checks, so it reads back here
                              * beside the rating basis and the frequency loading.
                              */
                             TiraFiling tiraFiling) {}

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
     * The SUM_ASSURED_BAND multiplier for a real amount, resolved by RANGE (V9).
     *
     * <p><b>Replaces matching a band by string, which never matched anything.</b> Underwriting
     * produced one of three values hardcoded in Java — LOW, MEDIUM, HIGH, on thresholds of two
     * and ten million — while a product author typed a band into a free-text box. A real
     * published product carried '5000000', matched none of the three, and priced every policy as
     * though the factor did not exist. The thresholds being in code was the deeper half: a
     * product's own bands are the product's business, and three fixed tiers cannot express a
     * real rating table.
     *
     * <p>Neutral 1.0 when no band covers the amount, matching {@link #resolveRatingMultiplier}:
     * not every product rates on sum assured, and a version published before V9 has no bounds at
     * all. A premium path that cannot tolerate a missing factor must say so itself.
     *
     * <p>Bounds are inclusive at both ends. Publish-time validation refuses overlapping bands, so
     * at most one row can cover any amount.
     */
    BigDecimal resolveSumAssuredMultiplier(UUID productVersionId, BigDecimal sumAssuredAmount);

    /** Whether this version carries a base rate table, and is therefore priced from one. */
    boolean isPriced(UUID productVersionId);

    /**
     * The rate per mille this version charges a life of this age, sex and smoker status.
     *
     * <p><b>The product's own price, which automatic issuance never used.</b> Issuance computed
     * every premium from a single flat {@code TZ_BASE_PREMIUM_RATE_PER_MILLE} in reference data,
     * so an actuary could author a full mortality table — age bands, sex, smoker status — and
     * watch it change nothing. {@code quotePremium} read it; nothing that issued a contract did.
     *
     * <p>Empty when no cell covers that combination, which is a REAL and important answer: it
     * means the table has a hole, and the caller must refuse rather than price at some default.
     * Pricing a life the actuary never priced is how a product ends up selling cover it has not
     * costed. Callers on an unpriced version should ask {@link #isPriced} first rather than
     * reading an empty here as "no table".
     *
     * @param sex null when unrecorded, which matches no cell — a priced product needs the fact,
     *            and there is deliberately no neutral value to fall back to.
     * @param smokerStatus never null from the issuance path, which maps an unrecorded status onto
     *                     {@link SmokerStatus#UNKNOWN} so a product that priced the undeclared
     *                     case is actually used. Null still matches no cell, for a caller that
     *                     genuinely has nothing to assert.
     */
    Optional<BigDecimal> resolveBaseRatePerMille(UUID productVersionId, int ageAtEntry,
                                                  Sex sex, SmokerStatus smokerStatus, Integer termMonths);

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

    /**
     * A version's cash-value configuration, present only for a savings product (V17). Its presence
     * is how a caller tells a savings contract from pure protection: {@code policy} reads it on each
     * premium to decide whether to value the policy at all, and {@code min years} gates when a value
     * first exists.
     */
    record CashValueConfigView(String basisReference, java.time.LocalDate basisDate,
                               String paidUpBasis, int minYearsForValue) {}

    /** The cash-value configuration for {@code productVersionId}, or empty for a non-savings product. */
    Optional<CashValueConfigView> getCashValueConfig(UUID productVersionId);

    /**
     * The cash value per 1,000 of sum assured at {@code policyYear} for a life entering at
     * {@code ageAtEntry} (null where the scale is not age-banded). Empty when the version has no
     * cash-value table, or no row for that year/age -- a gap the actuary did not price, which the
     * caller must treat as "no value", never as zero-by-default masquerading as priced.
     */
    Optional<BigDecimal> resolveCashValuePerMille(UUID productVersionId, int policyYear, Integer ageAtEntry);

    /**
     * The paid-up sum assured per 1,000 at {@code policyYear}/{@code ageAtEntry}, for the TABLE
     * paid-up basis. Empty when no row applies or the row carries no paid-up scale (the version uses
     * the PROPORTIONATE basis, which needs no table).
     */
    Optional<BigDecimal> resolvePaidUpPerMille(UUID productVersionId, int policyYear, Integer ageAtEntry);
}
