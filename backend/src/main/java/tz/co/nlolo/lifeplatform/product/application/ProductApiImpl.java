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

    @Override
    @Transactional
    public void publishVersion(UUID productId, IfrsMeasurementModel ifrsMeasurementModel, LocalDate effectiveDate, LocalDate retirementDate,
                                List<RatingFactorInput> ratingTable, List<BenefitInput> benefitSchedule, List<FundInput> fundDefinitions,
                                List<BaseRateInput> baseRates, String publishedBy) {
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
        } else if (!coveredFactorTypes.containsAll(List.of(FactorType.AGE, FactorType.SUM_ASSURED_BAND))) {
            // Unpriced version: unchanged from M2. Age is rated by multiplier alone.
            throw new InvalidProductVersionException("Rating table must cover at least AGE and SUM_ASSURED_BAND factor types");
        }
        rejectDuplicateRatingFactors(ratingTable);

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
        productVersionRepository.save(version);

        for (RatingFactorInput input : ratingTable) {
            ratingFactorRepository.save(new RatingFactor(tenantId, version.getProductVersionId(), input.factorType().name(), input.band(), input.multiplier()));
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
        ProductVersion version = productVersionRepository.findActiveAsOf(tenantId, productId, effectiveAsOf).stream()
            .findFirst()
            .orElseThrow(() -> new ProductNotFoundException(productId));
        ProductDefinition definition = productDefinitionRepository.findById(productId).orElseThrow(() -> new ProductNotFoundException(productId));
        return new ProductSnapshotView(productId, version.getProductVersionId(), version.getEffectiveDate(),
            IfrsMeasurementModel.valueOf(definition.getIfrsMeasurementModel()),
            version.getGracePeriodDays(), version.getMaxLoanToValuePercent(),
            ProductCategory.valueOf(definition.getCategory()), version.getSurrenderChargeScheduleJson());
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
            .map(f -> new RatingFactorInput(FactorType.valueOf(f.getFactorType()), f.getBand(), f.getMultiplier()))
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
    public BigDecimal resolveRatingMultiplier(UUID productVersionId, FactorType factorType, String band) {
        return ratingFactorRepository.findByProductVersionIdAndFactorType(productVersionId, factorType.name()).stream()
            .filter(f -> f.getBand().equals(band))
            .map(RatingFactor::getMultiplier)
            .findFirst()
            .orElse(BigDecimal.ONE); // No matching band -- neutral multiplier, not an error (see ProductApi.resolveRatingMultiplier's javadoc).
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
            ProductCategory.valueOf(definition.getCategory()), version.getSurrenderChargeScheduleJson());
    }

    private ProductSummaryView toSummaryView(ProductDefinition p) {
        return new ProductSummaryView(p.getProductId(), p.getProductCode(), p.getProductName(),
            ProductCategory.valueOf(p.getCategory()), ProductStatus.valueOf(p.getStatus()), p.getDefaultCurrency());
    }
}
