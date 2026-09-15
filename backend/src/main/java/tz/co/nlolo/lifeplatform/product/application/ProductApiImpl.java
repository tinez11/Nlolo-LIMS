package tz.co.nlolo.lifeplatform.product.application;

import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.product.api.*;
import tz.co.nlolo.lifeplatform.product.domain.*;
import tz.co.nlolo.lifeplatform.product.infrastructure.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ProductApiImpl implements ProductApi {

    private final ProductDefinitionRepository productDefinitionRepository;
    private final ProductVersionRepository productVersionRepository;
    private final RatingFactorRepository ratingFactorRepository;
    private final BenefitScheduleEntryRepository benefitScheduleEntryRepository;
    private final FundDefinitionRepository fundDefinitionRepository;
    private final BaseRateRepository baseRateRepository;

    public ProductApiImpl(ProductDefinitionRepository productDefinitionRepository, ProductVersionRepository productVersionRepository,
                           RatingFactorRepository ratingFactorRepository, BenefitScheduleEntryRepository benefitScheduleEntryRepository,
                           FundDefinitionRepository fundDefinitionRepository, BaseRateRepository baseRateRepository) {
        this.productDefinitionRepository = productDefinitionRepository;
        this.productVersionRepository = productVersionRepository;
        this.ratingFactorRepository = ratingFactorRepository;
        this.benefitScheduleEntryRepository = benefitScheduleEntryRepository;
        this.fundDefinitionRepository = fundDefinitionRepository;
        this.baseRateRepository = baseRateRepository;
    }

    @Override
    @Transactional
    public ProductSummaryView createProduct(String productCode, String productName, ProductCategory category, String defaultCurrency, String createdBy) {
        UUID tenantId = TenantContext.get();
        if (productDefinitionRepository.findByTenantIdAndProductCode(tenantId, productCode).isPresent()) {
            throw new DuplicateProductCodeException(productCode);
        }
        ProductDefinition product = new ProductDefinition(tenantId, productCode, productName, category.name(), defaultCurrency, createdBy);
        try {
            // The check above is a fast-path UX improvement, not the guarantee -- ux_product_code
            // (the unique index on (tenant_id, product_code)) is. Two concurrent requests can both
            // pass the check above and race to insert; the loser's DataIntegrityViolationException is
            // translated here so callers see the same domain exception regardless of timing. This MUST
            // be saveAndFlush, not save: productId is an in-memory-generated UUID (Hibernate's
            // UuidGenerator, no DB round-trip needed to assign it), so plain save() only queues the
            // INSERT in the flush action queue -- it doesn't hit the DB until the surrounding
            // @Transactional proxy commits, which is after this method (and this catch block) has
            // already returned. saveAndFlush forces the INSERT to execute synchronously, right here, so
            // a real unique-constraint violation is actually caught. Mirrors PartyApiImpl.registerCorporate.
            productDefinitionRepository.saveAndFlush(product);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateProductCodeException(productCode);
        }
        return toSummaryView(product);
    }

    @Override
    public List<ProductSummaryView> listActiveProducts(ProductCategory categoryFilter) {
        UUID tenantId = TenantContext.get();
        List<ProductDefinition> products = categoryFilter != null
            ? productDefinitionRepository.findByTenantIdAndStatusAndCategory(tenantId, "ACTIVE", categoryFilter.name())
            : productDefinitionRepository.findByTenantIdAndStatus(tenantId, "ACTIVE");
        return products.stream().map(this::toSummaryView).collect(Collectors.toList());
    }

    /**
     * No category filter, on purpose: a draft is an unfinished authoring task rather than a
     * catalogue entry, and there are only ever a handful. Filtering a to-do list by product
     * category answers a question nobody has.
     */
    @Override
    public List<ProductSummaryView> listDraftProducts() {
        return productDefinitionRepository.findByTenantIdAndStatus(TenantContext.get(), "DRAFT")
            .stream().map(this::toSummaryView).collect(Collectors.toList());
    }

    /**
     * Any status, deliberately. A retired product is exactly the case that needs this: it is
     * gone from the catalogue while the policies and the underwriting cases that reference it
     * are still on screen, so filtering by ACTIVE here would print a uuid on precisely the
     * records whose history someone is trying to read.
     */
    @Override
    public ProductSummaryView getProduct(UUID productId) {
        return productDefinitionRepository.findByTenantIdAndProductId(TenantContext.get(), productId)
            .map(this::toSummaryView)
            .orElseThrow(() -> new ProductNotFoundException(productId));
    }

    /**
     * The two convenience overloads, each carrying {@code @Transactional} itself.
     *
     * <p>They live here rather than as {@code default} methods on the interface for the
     * reason recorded on {@link ProductApi#publishVersion}: a {@code default} method has
     * no annotation for the proxy to see, so its delegating call becomes a
     * self-invocation and the whole publish runs untransacted. With the annotation here,
     * the transaction is already open by the time the delegation happens, so the retire
     * -then-insert sequence below is atomic for every caller regardless of which
     * overload they use.
     */
    @Override
    @Transactional
    public void publishVersion(UUID productId, IfrsMeasurementModel ifrsMeasurementModel, LocalDate effectiveDate, LocalDate retirementDate,
                                List<RatingFactorInput> ratingTable, List<BenefitInput> benefitSchedule,
                                List<FundInput> fundDefinitions, String publishedBy) {
        publishVersion(productId, ifrsMeasurementModel, effectiveDate, retirementDate, ratingTable, benefitSchedule,
            fundDefinitions, List.of(), EligibilityBounds.none(), publishedBy);
    }

    @Override
    @Transactional
    public void publishVersion(UUID productId, IfrsMeasurementModel ifrsMeasurementModel, LocalDate effectiveDate, LocalDate retirementDate,
                                List<RatingFactorInput> ratingTable, List<BenefitInput> benefitSchedule,
                                List<FundInput> fundDefinitions, List<BaseRateInput> baseRates, String publishedBy) {
        publishVersion(productId, ifrsMeasurementModel, effectiveDate, retirementDate, ratingTable, benefitSchedule,
            fundDefinitions, baseRates, EligibilityBounds.none(), publishedBy);
    }

    @Override
    @Transactional
    public void publishVersion(UUID productId, IfrsMeasurementModel ifrsMeasurementModel, LocalDate effectiveDate, LocalDate retirementDate,
                                List<RatingFactorInput> ratingTable, List<BenefitInput> benefitSchedule, List<FundInput> fundDefinitions,
                                List<BaseRateInput> baseRates, EligibilityBounds bounds, String publishedBy) {
        UUID tenantId = TenantContext.get();
        ProductDefinition product = productDefinitionRepository.findById(productId)
            .filter(p -> p.getTenantId().equals(tenantId))
            .orElseThrow(() -> new ProductNotFoundException(productId));

        // Deliverable 3 invariant: fund definitions restricted to UNIT_LINKED products.
        if (fundDefinitions != null && !fundDefinitions.isEmpty() && !"UNIT_LINKED".equals(product.getCategory())) {
            throw new InvalidProductVersionException("Fund definitions are only valid for UNIT_LINKED products");
        }
        // Deliverable 3 invariant: full rating-factor coverage validated at publish --
        // every declared FactorType must have at least one band defined so the rules
        // engine (Task 4) never silently falls back to a neutral 1.0 for a factor type
        // this product intended to rate on.
        java.util.Set<FactorType> coveredFactorTypes = ratingTable.stream().map(RatingFactorInput::factorType).collect(Collectors.toSet());
        boolean priced = baseRates != null && !baseRates.isEmpty();

        if (priced) {
            // M13. Age IS rated on a priced version -- it is a key of the base rate
            // table -- so requiring an AGE multiplier as well would demand the one
            // thing the next check forbids. Only SUM_ASSURED_BAND stays required.
            if (!coveredFactorTypes.contains(FactorType.SUM_ASSURED_BAND)) {
                throw new InvalidProductVersionException("Rating table must cover at least the SUM_ASSURED_BAND factor type");
            }
            // The double-count guard, and the single most likely defect in this
            // design. AGE and SMOKER_STATUS are keys of the base rate table, so a
            // rating_table row for either would be applied a second time on top of
            // the rate it already selected -- silently inflating or deflating the
            // premium with nothing failing. Refused rather than left to review.
            java.util.Set<FactorType> doubleCounted = new java.util.LinkedHashSet<>(coveredFactorTypes);
            doubleCounted.retainAll(List.of(FactorType.AGE, FactorType.SMOKER_STATUS));
            if (!doubleCounted.isEmpty()) {
                throw new InvalidProductVersionException(
                    "A version with base rates must not also carry " + doubleCounted
                        + " rating factors -- those dimensions are keys of the base rate table and would be counted twice");
            }
            rejectOverlappingAgeBands(baseRates);
            rejectPricedVersionWithoutEntryAgeBounds(bounds);
        } else if (!coveredFactorTypes.containsAll(List.of(FactorType.AGE, FactorType.SUM_ASSURED_BAND))) {
            // Unpriced version: unchanged from M2. Age is rated by multiplier alone.
            throw new InvalidProductVersionException("Rating table must cover at least AGE and SUM_ASSURED_BAND factor types");
        }
        rejectNonPositiveMultipliers(ratingTable);
        rejectDuplicateRatingFactors(ratingTable);
        rejectMalformedAgeBands(ratingTable);
        rejectMalformedSumAssuredBands(ratingTable);

        // Version rollover: ux_product_version_active permits at most one
        // is_active_for_new_business = true row per product_id. Retire whatever version
        // currently holds that flag before inserting the new one, in the same transaction,
        // so a second publishVersion call on an existing product succeeds instead of
        // crashing on the partial unique index (C1 in the final review). This MUST be
        // saveAndFlush, not save: Hibernate's flush executes all queued INSERTs before any
        // queued UPDATEs regardless of call order, so a plain save() here would still let
        // the new version's INSERT reach the DB (and violate the partial unique index)
        // before this retirement UPDATE does. saveAndFlush forces the UPDATE to execute
        // synchronously, right here, ahead of the new version's insert below.
        for (ProductVersion currentActive : productVersionRepository.findByTenantIdAndProductIdAndActiveForNewBusinessTrue(tenantId, productId)) {
            currentActive.retireFromNewBusiness();
            productVersionRepository.saveAndFlush(currentActive);
        }

        int gracePeriodDays = 30; // Deliverable 3 doesn't specify a grace-period source yet at this layer -- see Global Constraints; this is a fixed, flagged default, not read from an OpenAPI field (ProductVersionSpec has no gracePeriodDays field).
        ProductVersion version = new ProductVersion(tenantId, productId, effectiveDate, retirementDate, gracePeriodDays, null, publishedBy);
        // What this version will accept. Never null -- callers that state nothing pass
        // EligibilityBounds.none(), because an unbounded version is a real design.
        version.applyEligibilityBounds(bounds != null ? bounds : EligibilityBounds.none());
        productVersionRepository.save(version);

        for (RatingFactorInput input : ratingTable) {
            ratingFactorRepository.save(new RatingFactor(tenantId, version.getProductVersionId(),
                input.factorType().name(), input.band(), input.multiplier(), input.ageFrom(), input.ageTo(),
                input.sumAssuredFrom(), input.sumAssuredTo()));
        }
        if (baseRates != null) {
            for (BaseRateInput input : baseRates) {
                baseRateRepository.save(new BaseRate(tenantId, version.getProductVersionId(),
                    input.ageFrom(), input.ageTo(), input.sex().name(), input.smokerStatus().name(),
                    input.ratePerMille()));
            }
        }
        for (BenefitInput input : benefitSchedule) {
            benefitScheduleEntryRepository.save(new BenefitScheduleEntry(tenantId, version.getProductVersionId(), input.benefitType().name(), input.calculationMethod()));
        }
        if (fundDefinitions != null) {
            for (FundInput input : fundDefinitions) {
                fundDefinitionRepository.save(new FundDefinition(tenantId, version.getProductVersionId(), input.fundCode(), input.currentNav()));
            }
        }

        product.activateWithMeasurementModel(ifrsMeasurementModel.name());
        productDefinitionRepository.save(product);
    }

    @Override
    public ProductSnapshotView getActiveSnapshot(UUID productId, LocalDate asOfDate) {
        UUID tenantId = TenantContext.get();
        LocalDate effectiveAsOf = asOfDate != null ? asOfDate : LocalDate.now();

        /*
         * The definition is resolved FIRST, and the order is the fix rather than a tidy-up.
         *
         * Both lookups used to throw ProductNotFoundException, and the version lookup ran
         * first -- so a product that exists but has no version in force today was reported
         * as a product that does not exist. On the console that became "this record does not
         * exist, or it is not available to your role", shown to a staff member who had just
         * clicked the product in the catalogue: the record plainly existed, and their role
         * had nothing to do with it. A GROUP_LIFE product whose only version was effective
         * the next day hit exactly this.
         *
         * Asking "is there such a product" before "is any version of it in force" is what
         * lets the two answers stay different.
         */
        ProductDefinition definition = productDefinitionRepository.findById(productId)
            .orElseThrow(() -> new ProductNotFoundException(productId));
        ProductVersion version = productVersionRepository.findActiveAsOf(tenantId, productId, effectiveAsOf).stream()
            .findFirst()
            .orElseThrow(() -> new NoActiveProductVersionException(productId, effectiveAsOf,
                productVersionRepository
                    .findFirstByTenantIdAndProductIdAndEffectiveDateAfterOrderByEffectiveDateAsc(
                        tenantId, productId, effectiveAsOf)
                    .map(ProductVersion::getEffectiveDate)
                    .orElse(null)));
        return new ProductSnapshotView(productId, version.getProductVersionId(), version.getEffectiveDate(),
            IfrsMeasurementModel.valueOf(definition.getIfrsMeasurementModel()),
            version.getGracePeriodDays(), version.getMaxLoanToValuePercent(),
            ProductCategory.valueOf(definition.getCategory()), version.getSurrenderChargeScheduleJson(),
            version.getEligibilityBounds());
    }

    /**
     * Order of operations is fixed and stated, because every step is a place a
     * silent mispricing could live:
     *
     * 1. Resolve the version active as of `asOf` -- the same lookup getActiveSnapshot
     *    uses, so a quote and an issuance on the same date price on the same rules.
     * 2. Derive entry age from dateOfBirth and asOf. Never taken from the caller.
     * 3. Look up the rate cell for (age, sex, smoker). No match is a 422.
     * 4. annualBase = sumAssured / 1000 * ratePerMille.
     * 5. Apply OCCUPATION_CLASS then SUM_ASSURED_BAND multipliers, strictly.
     * 6. Divide by the frequency's instalments per year.
     * 7. Round ONCE, at the end, HALF_UP to 2dp. Intermediate values keep full
     *    precision: rounding at each step would drift by cents that compound over
     *    a 20-year premium term.
     *
     * No policy fee -- no product field holds one (open item in the M13 spec).
     */
    @Override
    public PremiumQuoteView quotePremium(PremiumQuoteInput input) {
        UUID tenantId = TenantContext.get();
        LocalDate asOf = input.asOf() != null ? input.asOf() : LocalDate.now();

        ProductVersion version = productVersionRepository.findActiveAsOf(tenantId, input.productId(), asOf).stream()
            .findFirst()
            .orElseThrow(() -> new ProductNotFoundException(input.productId()));
        UUID versionId = version.getProductVersionId();

        if (!baseRateRepository.existsByProductVersionId(versionId)) {
            throw new PremiumNotQuotableException("Product version " + versionId
                + " carries no base rate table, so it cannot be priced");
        }

        int ageAtEntry = java.time.Period.between(input.dateOfBirth(), asOf).getYears();
        if (ageAtEntry < 0) {
            throw new PremiumNotQuotableException("Date of birth " + input.dateOfBirth() + " is after " + asOf);
        }

        BaseRate cell = baseRateRepository
            .findApplicable(versionId, ageAtEntry, input.sex().name(), input.smokerStatus().name())
            .orElseThrow(() -> new PremiumNotQuotableException("No base rate for age " + ageAtEntry
                + ", " + input.sex() + ", " + input.smokerStatus() + " on product version " + versionId));

        BigDecimal annualBase = input.sumAssuredAmount()
            .divide(new BigDecimal("1000"), java.math.MathContext.DECIMAL64)
            .multiply(cell.getRatePerMille());

        List<AppliedFactor> applied = new java.util.ArrayList<>();
        BigDecimal annual = annualBase;
        annual = annual.multiply(strictMultiplier(versionId, FactorType.OCCUPATION_CLASS, input.occupationClass(), applied));
        annual = annual.multiply(strictMultiplier(versionId, FactorType.SUM_ASSURED_BAND, input.sumAssuredBand(), applied));

        int instalments = input.frequency().instalmentsPerYear();
        BigDecimal instalment = annual
            .divide(BigDecimal.valueOf(instalments), 2, java.math.RoundingMode.HALF_UP);

        return new PremiumQuoteView(versionId, input.sumAssuredCurrency(),
            ageAtEntry, cell.getAgeFrom(), cell.getAgeTo(), cell.getRatePerMille(),
            annualBase.setScale(2, java.math.RoundingMode.HALF_UP), List.copyOf(applied),
            annual.setScale(2, java.math.RoundingMode.HALF_UP),
            input.frequency(), instalments, instalment);
    }

    @Override
    public VersionRatingView getVersionRating(UUID productId, UUID versionId) {
        UUID tenantId = TenantContext.get();
        ProductVersion version = productVersionRepository.findById(versionId)
            .filter(v -> v.getTenantId().equals(tenantId) && v.getProductId().equals(productId))
            .orElseThrow(() -> new ProductNotFoundException(versionId));

        List<BaseRateInput> rates = baseRateRepository.findByProductVersionId(versionId).stream()
            .map(r -> new BaseRateInput(r.getAgeFrom(), r.getAgeTo(), Sex.valueOf(r.getSex()),
                SmokerStatus.valueOf(r.getSmokerStatus()), r.getRatePerMille()))
            .toList();
        List<RatingFactorInput> factors = ratingFactorRepository.findByProductVersionId(versionId).stream()
            // Bounds included, both kinds: this is the read an actuary reviews a version's
            // rating basis on, and a SUM_ASSURED_BAND row's bounds are what it now rates by.
            // Showing only the band text would show the label while hiding the rule -- and the
            // label is exactly what turned out to be untrustworthy (V9).
            .map(f -> new RatingFactorInput(FactorType.valueOf(f.getFactorType()), f.getBand(),
                f.getMultiplier(), f.getAgeFrom(), f.getAgeTo(),
                f.getSumAssuredFrom(), f.getSumAssuredTo()))
            .toList();
        List<BenefitInput> benefits = benefitScheduleEntryRepository.findByProductVersionId(versionId).stream()
            .map(b -> new BenefitInput(BenefitType.valueOf(b.getBenefitType()), b.getCalculationMethod()))
            .toList();

        return new VersionRatingView(productId, versionId, version.getEffectiveDate(), rates, factors, benefits);
    }

    /**
     * Two bands covering the same age in the same (sex, smoker) cell would make the
     * premium depend on which row the query happens to return first -- a silent
     * mispricing rather than an error, and invisible to the unique constraint,
     * which only stops an identical starting age.
     *
     * Enforced here rather than in SQL: a true non-overlap constraint needs an
     * EXCLUDE ... USING gist over an int4range, which requires the btree_gist
     * extension for the equality parts of the key. Not worth a new extension on
     * every environment for a rule this cheap to check where the rows are authored.
     */
    /**
     * Two rating_table rows sharing a (factorType, band) make the multiplier that applies depend on
     * which row the query returns first -- {@code strictMultiplier} and {@code
     * resolveRatingMultiplier} both filter to the band and then take {@code findFirst()}. Same
     * silent-mispricing shape as overlapping age bands, and until this check nothing stopped it:
     * {@code rating_table} carried only a NON-unique index on (product_version_id, factor_type),
     * and the authoring form lets a user add the same band twice with different multipliers.
     *
     * <p>Found by sweeping for this defect class after three unordered-query bugs turned up in one
     * day, rather than by a failing test -- a duplicate band is not reachable by accident, so it
     * would have sat here until someone authored one and quietly got the wrong premium.
     *
     * <p>{@code V4__rating_table_unique_band.sql} adds the constraint the table should always have
     * had. This check exists as well as that one so the failure names the offending band instead of
     * surfacing as a constraint violation.
     */
    /**
     * A rating multiplier must be a positive number.
     *
     * <p><b>A zero here zeroes the premium, and it happened.</b> A product was published with its
     * AGE band at 0.0000; an applicant was accepted against it; the premium computed to nil; the
     * insert hit {@code chk_premium_amount_positive} inside an AFTER_COMMIT listener, so the
     * decision stood, no policy was created, and nothing surfaced. The underwriter read ACCEPT
     * and believed a policy existed.
     *
     * <p>Nothing stopped it at any layer: the console's schema had a bare
     * {@code z.coerce.number()}, this method checked coverage, duplicates and age ranges but
     * never the number, and {@code rating_table} carried no CHECK. A negative multiplier would
     * have reached just as far and priced a policy below nothing.
     *
     * <p>There is no product in which a rating factor of zero is a real design. A band that adds
     * nothing is expressed by 1.0000, or by leaving the row out — both of which say so, where a
     * zero only looks like a number somebody meant.
     *
     * <p>{@code rating_table_multiplier_positive} backs this at the database. Checked here as
     * well so the failure names the band instead of surfacing as a constraint violation, which is
     * the same arrangement as the duplicate-band and age-bound rules below.
     */
    private static void rejectNonPositiveMultipliers(List<RatingFactorInput> ratingTable) {
        for (RatingFactorInput factor : ratingTable) {
            if (factor.multiplier() == null || factor.multiplier().signum() <= 0) {
                throw new InvalidProductVersionException("Rating factor " + factor.factorType()
                    + " band '" + factor.band() + "' has a multiplier of "
                    + (factor.multiplier() != null ? factor.multiplier().toPlainString() : "none")
                    + " -- a rating multiplier must be greater than zero, or it prices every policy"
                    + " in that band at nothing. Use 1.0000 for a band that does not load.");
            }
        }
    }

    private static void rejectDuplicateRatingFactors(List<RatingFactorInput> ratingTable) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (RatingFactorInput factor : ratingTable) {
            if (!seen.add(factor.factorType() + "|" + factor.band())) {
                throw new InvalidProductVersionException("Rating table has two rows for "
                    + factor.factorType() + " band '" + factor.band()
                    + "' -- which multiplier applies would depend on row order");
            }
        }
    }

    /**
     * AGE rating factors must carry an age range, and the ranges must not overlap.
     *
     * <p>The shape half is also a database CHECK ({@code rating_table_age_bounds_shape}),
     * checked here so the failure names the offending band instead of surfacing as a
     * constraint violation. The overlap half exists only here, for the same reason it does
     * for base rates: a true non-overlap constraint needs an EXCLUDE ... USING gist over an
     * int4range, which needs btree_gist on every environment, and the rule is cheap to
     * check where the rows are authored.
     *
     * <p>Two AGE bands covering one applicant would make the multiplier depend on which row
     * the query returned first. That is the same silent-mispricing shape found four times
     * over on this platform, and it decides an underwriting outcome here, not just a label.
     */
    /**
     * SUM_ASSURED_BAND rating factors must carry an amount range, and the ranges must not overlap.
     *
     * <p>The same rule as {@link #rejectMalformedAgeBands}, for the same defect one factor type
     * over (V9). A band was matched by exact string against three values hardcoded in
     * underwriting, so a product author's band could never match and the factor silently did
     * nothing. Bounds are now what the platform resolves on, so a row without them would be a row
     * that rates nobody — which is precisely the failure being removed, and must not be
     * publishable.
     *
     * <p>The shape half is also a database CHECK ({@code rating_table_sum_assured_bounds_shape}),
     * checked here so the failure names the offending band. The overlap half exists only here,
     * for the same reason it does for ages and base rates: a true non-overlap constraint needs an
     * EXCLUDE ... USING gist, and the rule is cheap to check where the rows are authored.
     *
     * <p><b>A range is demanded of a row that RATES, and not of a neutral one.</b> The defect
     * being removed is a row that claims to change a price and silently does not; a row at
     * exactly 1.0000 changes no price whether it resolves or not, so demanding bounds of it would
     * be ceremony. It would also invalidate every version published before V9 on republish, and
     * around eighty existing fixtures whose SUM_ASSURED_BAND row exists only to satisfy the
     * coverage rule — none of which would be made more correct by inventing an amount range for
     * them.
     */
    private static void rejectMalformedSumAssuredBands(List<RatingFactorInput> ratingTable) {
        List<RatingFactorInput> bands = ratingTable.stream()
            .filter(f -> f.factorType() == FactorType.SUM_ASSURED_BAND)
            .toList();

        for (RatingFactorInput f : bands) {
            boolean neutral = f.multiplier() != null && f.multiplier().compareTo(BigDecimal.ONE) == 0;
            boolean unbounded = f.sumAssuredFrom() == null || f.sumAssuredTo() == null;
            if (unbounded && !neutral) {
                throw new InvalidProductVersionException("SUM_ASSURED_BAND rating factor '" + f.band()
                    + "' carries a multiplier of " + f.multiplier().toPlainString()
                    + " but no amount range. A sum assured is rated by RANGE, not by matching the"
                    + " band text, so this row would rate nobody and the multiplier would never"
                    + " reach a premium -- which is the defect this rule exists to stop.");
            }
            if (unbounded) {
                continue;
            }
            if (f.sumAssuredFrom().signum() < 0) {
                throw new InvalidProductVersionException("SUM_ASSURED_BAND rating factor '" + f.band()
                    + "' starts below zero");
            }
            if (f.sumAssuredTo().compareTo(f.sumAssuredFrom()) < 0) {
                throw new InvalidProductVersionException("SUM_ASSURED_BAND rating factor '" + f.band()
                    + "' ends before it begins");
            }
        }

        // Only bounded rows can overlap; an unbounded neutral one covers nothing.
        List<RatingFactorInput> bounded = bands.stream()
            .filter(f -> f.sumAssuredFrom() != null && f.sumAssuredTo() != null)
            .toList();
        for (RatingFactorInput a : bounded) {
            for (RatingFactorInput b : bounded) {
                if (a == b) continue;
                if (a.sumAssuredFrom().compareTo(b.sumAssuredTo()) <= 0
                        && b.sumAssuredFrom().compareTo(a.sumAssuredTo()) <= 0) {
                    throw new InvalidProductVersionException("SUM_ASSURED_BAND ranges '" + a.band()
                        + "' and '" + b.band() + "' overlap -- a sum assured in both would price"
                        + " differently depending on row order");
                }
            }
        }
    }

    private static void rejectMalformedAgeBands(List<RatingFactorInput> ratingTable) {
        List<RatingFactorInput> ageBands = ratingTable.stream()
            .filter(f -> f.factorType() == FactorType.AGE)
            .toList();

        for (RatingFactorInput f : ageBands) {
            if (f.ageFrom() == null || f.ageTo() == null) {
                throw new InvalidProductVersionException("AGE rating factor '" + f.band()
                    + "' needs an age range -- age is rated by range, not by matching the band text");
            }
            if (f.ageFrom() < 0 || f.ageTo() < f.ageFrom()) {
                throw new InvalidProductVersionException("AGE rating factor '" + f.band()
                    + "' has an impossible range " + f.ageFrom() + "-" + f.ageTo());
            }
        }
        for (RatingFactorInput a : ageBands) {
            for (RatingFactorInput b : ageBands) {
                if (a == b) continue;
                if (a.ageFrom() <= b.ageTo() && b.ageFrom() <= a.ageTo()) {
                    throw new InvalidProductVersionException("AGE rating factors " + a.ageFrom() + "-"
                        + a.ageTo() + " and " + b.ageFrom() + "-" + b.ageTo()
                        + " overlap -- an applicant in both would be rated by whichever row came back first");
                }
            }
        }
        // A version that rates on AGE at all should cover the ages it sells to, but nothing
        // on this platform records a product's minimum or maximum entry age, so there is no
        // range to check completeness against. An uncovered age resolves to the neutral 1.0
        // rather than failing, which is the documented contract -- worth revisiting if entry
        // age limits ever become product data.
    }

    /**
     * A priced version must say what ages it sells to.
     *
     * <p>Without bounds there is no declared range for {@link #rejectUncoveredEntryAges} to check
     * a rate table against, so the table's own span silently becomes the product's selling range.
     * That is not hypothetical: a real published version declares it accepts entry ages 18-78
     * while pricing no woman under 56, and nothing could tell the difference between "we price
     * 18-30 deliberately" and "we forgot the rest".
     *
     * <p>Only priced versions. An unpriced version has no rate table to be incomplete against, and
     * bounds stay optional there exactly as {@link EligibilityBounds} describes.
     */
    private static void rejectPricedVersionWithoutEntryAgeBounds(EligibilityBounds bounds) {
        if (bounds == null || bounds.minEntryAge() == null || bounds.maxEntryAge() == null) {
            throw new InvalidProductVersionException(
                "A version priced from a base rate table must declare its minimum and maximum"
                    + " entry age. Without them the rate table's own span silently becomes the"
                    + " product's selling range, and nothing can tell a deliberate range from an"
                    + " incomplete one.");
        }
    }

    private static void rejectOverlappingAgeBands(List<BaseRateInput> baseRates) {
        for (BaseRateInput a : baseRates) {
            if (a.ageTo() < a.ageFrom()) {
                throw new InvalidProductVersionException(
                    "Base rate band " + a.ageFrom() + "-" + a.ageTo() + " ends before it begins");
            }
            for (BaseRateInput b : baseRates) {
                if (a == b || a.sex() != b.sex() || a.smokerStatus() != b.smokerStatus()) continue;
                if (a.ageFrom() <= b.ageTo() && b.ageFrom() <= a.ageTo()) {
                    throw new InvalidProductVersionException("Base rate bands " + a.ageFrom() + "-" + a.ageTo()
                        + " and " + b.ageFrom() + "-" + b.ageTo() + " overlap for " + a.sex() + "/" + a.smokerStatus()
                        + " -- an age in both would price differently depending on row order");
                }
            }
        }
    }

    /**
     * Unlike {@link #resolveRatingMultiplier}, a missing band here is an ERROR.
     * That method's neutral-1.0 is correct for underwriting risk scoring; on a
     * premium it would quietly price a real contract as if the factor did not
     * apply.
     */
    private BigDecimal strictMultiplier(UUID versionId, FactorType factorType, String band, List<AppliedFactor> applied) {
        if (band == null || band.isBlank()) {
            throw new PremiumNotQuotableException(factorType + " is required to price this product");
        }
        BigDecimal multiplier = ratingFactorRepository.findByProductVersionIdAndFactorType(versionId, factorType.name()).stream()
            .filter(f -> f.getBand().equals(band))
            .map(RatingFactor::getMultiplier)
            .findFirst()
            .orElseThrow(() -> new PremiumNotQuotableException(
                "No " + factorType + " multiplier for band '" + band + "' on product version " + versionId));
        applied.add(new AppliedFactor(factorType, band, multiplier));
        return multiplier;
    }

    @Override
    public BigDecimal resolveAgeMultiplier(UUID productVersionId, int age) {
        List<RatingFactor> covering = ratingFactorRepository.findAgeBandCovering(productVersionId, age);
        if (covering.isEmpty()) {
            // Neutral, exactly as resolveRatingMultiplier is for an unmatched band. This is
            // also the path every version published before V5 takes: its AGE rows have no
            // bounds, so they cover nobody and those versions keep rating age the way they
            // always have, rather than having decisions change underneath them.
            return BigDecimal.ONE;
        }
        if (covering.size() > 1) {
            // Overlaps are rejected at publish. Reaching here means a version predates that
            // check or was written around it, and picking one of two would be a silent
            // mis-rating -- the failure mode this platform has now found four times over.
            throw new InvalidProductVersionException("Product version " + productVersionId
                + " has " + covering.size() + " AGE bands covering age " + age
                + " -- which multiplier applies is undefined");
        }
        return covering.get(0).getMultiplier();
    }

    @Override
    public BigDecimal resolveRatingMultiplier(UUID productVersionId, FactorType factorType, String band) {
        return ratingFactorRepository.findByProductVersionIdAndFactorType(productVersionId, factorType.name()).stream()
            .filter(f -> f.getBand().equals(band))
            .map(RatingFactor::getMultiplier)
            .findFirst()
            .orElse(BigDecimal.ONE); // No matching band -- neutral multiplier, not an error (see ProductApi.resolveRatingMultiplier's javadoc).
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns a list and inspects it rather than taking {@code findFirst()}, for the same
     * reason {@code findAgeBandCovering} does: publish-time validation refuses overlapping bands,
     * so the steady state is 0 or 1 rows — but the invariant is enforced where the rows are
     * authored, not assumed here. A version published before that validation existed could still
     * hold overlaps, and silently taking one of two matches is the scan-order mispricing this
     * platform has now found five times.
     */
    @Override
    public BigDecimal resolveSumAssuredMultiplier(UUID productVersionId, BigDecimal sumAssuredAmount) {
        if (sumAssuredAmount == null) {
            return BigDecimal.ONE;
        }
        List<RatingFactor> covering = ratingFactorRepository
            .findByProductVersionIdAndFactorType(productVersionId, FactorType.SUM_ASSURED_BAND.name())
            .stream()
            .filter(f -> f.getSumAssuredFrom() != null && f.getSumAssuredTo() != null)
            .filter(f -> f.getSumAssuredFrom().compareTo(sumAssuredAmount) <= 0
                && f.getSumAssuredTo().compareTo(sumAssuredAmount) >= 0)
            .toList();
        if (covering.size() > 1) {
            throw new InvalidProductVersionException("Product version " + productVersionId
                + " has " + covering.size() + " SUM_ASSURED_BAND rows covering "
                + sumAssuredAmount.toPlainString()
                + " -- which multiplier applies would depend on row order");
        }
        return covering.isEmpty() ? BigDecimal.ONE : covering.get(0).getMultiplier();
    }

    @Override
    public boolean isPriced(UUID productVersionId) {
        return baseRateRepository.existsByProductVersionId(productVersionId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>A null sex or smoker status returns empty rather than guessing a cell. The caller then
     * refuses, which is the point: on a priced product those two facts are half the key to the
     * price, and a policy issued without them would be priced as somebody else.
     */
    @Override
    public Optional<BigDecimal> resolveBaseRatePerMille(UUID productVersionId, int ageAtEntry,
                                                         Sex sex, SmokerStatus smokerStatus) {
        if (sex == null || smokerStatus == null) {
            return Optional.empty();
        }
        return baseRateRepository
            .findApplicable(productVersionId, ageAtEntry, sex.name(), smokerStatus.name())
            .map(BaseRate::getRatePerMille);
    }

    @Override
    public ProductSnapshotView getSnapshotByVersionId(UUID productVersionId) {
        UUID tenantId = TenantContext.get();
        ProductVersion version = productVersionRepository.findById(productVersionId)
            .filter(v -> v.getTenantId().equals(tenantId))
            .orElseThrow(() -> new ProductNotFoundException(productVersionId));
        ProductDefinition definition = productDefinitionRepository.findById(version.getProductId())
            .orElseThrow(() -> new ProductNotFoundException(version.getProductId()));
        return new ProductSnapshotView(version.getProductId(), productVersionId, version.getEffectiveDate(),
            IfrsMeasurementModel.valueOf(definition.getIfrsMeasurementModel()), version.getGracePeriodDays(), version.getMaxLoanToValuePercent(),
            ProductCategory.valueOf(definition.getCategory()), version.getSurrenderChargeScheduleJson(),
            // The by-version-id lookup carries the bounds too. It is what
            // PolicyController.manualIssue resolves, so omitting them here would leave the
            // issue path unable to see the very bounds its gates are meant to check.
            version.getEligibilityBounds());
    }

    private ProductSummaryView toSummaryView(ProductDefinition p) {
        return new ProductSummaryView(p.getProductId(), p.getProductCode(), p.getProductName(),
            ProductCategory.valueOf(p.getCategory()), ProductStatus.valueOf(p.getStatus()), p.getDefaultCurrency());
    }
}
