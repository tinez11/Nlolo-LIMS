package tz.co.nlolo.lifeplatform.product;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.product.api.*;
import tz.co.nlolo.lifeplatform.product.domain.ProductVersion;
import tz.co.nlolo.lifeplatform.product.infrastructure.BaseRateRepository;
import tz.co.nlolo.lifeplatform.product.infrastructure.ProductVersionRepository;
import tz.co.nlolo.lifeplatform.product.infrastructure.RatingFactorRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(classes = Application.class)
class ProductApiIntegrationTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void applyMigrations() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/product/V2__base_rate_table.sql",
            "db-migrations/product/V3__base_rate_structured_age.sql",
            "db-migrations/product/V4__rating_table_unique_band.sql",
            "db-migrations/product/V5__rating_table_age_bounds.sql");
    }

    @BeforeEach
    void setTenant() { TenantContext.set(UUID.randomUUID()); }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @Autowired
    private ProductApi productApi;

    @Autowired
    private ProductVersionRepository productVersionRepository;

    @Autowired
    private BaseRateRepository baseRateRepository;

    @Autowired
    private RatingFactorRepository ratingFactorRepository;

    @Test
    void createProductStartsInDraft() {
        ProductSummaryView product = productApi.createProduct("TERM-01", "Simple Term Life", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        assertEquals(ProductStatus.DRAFT, product.status());
        assertEquals("TERM-01", product.productCode());
    }

    @Test
    void draftProductIsExcludedFromActiveListing() {
        productApi.createProduct("TERM-02", "Another Term Life", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        List<ProductSummaryView> active = productApi.listActiveProducts(ProductCategory.TERM_LIFE);
        assertTrue(active.stream().noneMatch(p -> p.productCode().equals("TERM-02")));
    }

    @Test
    void publishVersionActivatesProductAndAppearsInListing() {
        ProductSummaryView product = productApi.createProduct("TERM-03", "Published Term Life", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary@nlolo.co.tz");

        List<ProductSummaryView> active = productApi.listActiveProducts(ProductCategory.TERM_LIFE);
        assertTrue(active.stream().anyMatch(p -> p.productCode().equals("TERM-03") && p.status() == ProductStatus.ACTIVE));
    }

    @Test
    void publishVersionRejectsFundDefinitionsOnNonUnitLinkedProduct() {
        ProductSummaryView product = productApi.createProduct("TERM-04", "Term with bad fund", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        assertThrows(InvalidProductVersionException.class, () ->
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                        new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
                List.of(new ProductApi.FundInput("FUND-A", BigDecimal.TEN)),
                "actuary@nlolo.co.tz"));
    }

    // ---- M13: the base rate table premiums are computed from -------------------

    /**
     * The double-count guard, and the single most likely defect in the pricing
     * design: AGE and SMOKER_STATUS are KEYS of the base rate table, so a
     * rating_table multiplier for either dimension would be applied a second time
     * on top of the rate it already selected -- silently, with nothing failing.
     */
    @Test
    void publishVersionRejectsAgeMultiplierAlongsideBaseRates() {
        ProductSummaryView product = productApi.createProduct("TERM-M13-A", "Double-counted age", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        assertThrows(InvalidProductVersionException.class, () ->
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                        new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
                null,
                List.of(new ProductApi.BaseRateInput(30, 39, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("17.4000"))),
                "actuary@nlolo.co.tz"));
    }

    @Test
    void publishVersionRejectsSmokerMultiplierAlongsideBaseRates() {
        ProductSummaryView product = productApi.createProduct("TERM-M13-B", "Double-counted smoker", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        assertThrows(InvalidProductVersionException.class, () ->
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.SMOKER_STATUS, "SMOKER", new BigDecimal("1.5")),
                        new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
                null,
                List.of(new ProductApi.BaseRateInput(30, 39, Sex.MALE, SmokerStatus.SMOKER, new BigDecimal("22.1000"))),
                "actuary@nlolo.co.tz"));
    }

    /**
     * A priced version does NOT need an AGE multiplier -- age is rated by the base
     * rate table's own key. Requiring one while the guard above forbids it would
     * make base rates unpublishable, which is exactly the contradiction the two
     * checks were first written with.
     */
    @Test
    void publishVersionWithBaseRatesNeedsNoAgeMultiplier() {
        ProductSummaryView product = productApi.createProduct("TERM-M13-C", "Priced term life", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null,
            List.of(new ProductApi.BaseRateInput(18, 25, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("15.2000")),
                    new ProductApi.BaseRateInput(18, 25, Sex.MALE, SmokerStatus.NON_SMOKER, new BigDecimal("16.8000"))),
            "actuary@nlolo.co.tz");

        List<ProductSummaryView> active = productApi.listActiveProducts(ProductCategory.TERM_LIFE);
        assertTrue(active.stream().anyMatch(p -> p.productCode().equals("TERM-M13-C") && p.status() == ProductStatus.ACTIVE));

        UUID versionId = productVersionRepository
            .findByTenantIdAndProductIdAndActiveForNewBusinessTrue(TenantContext.get(), product.productId())
            .get(0).getProductVersionId();
        assertEquals(2, baseRateRepository.findByProductVersionId(versionId).size());
    }

    /**
     * A priced version still needs SUM_ASSURED_BAND: that dimension is NOT a key of
     * the base rate table, so dropping the requirement would leave it unrated.
     */
    @Test
    void publishVersionWithBaseRatesStillRequiresSumAssuredBand() {
        ProductSummaryView product = productApi.createProduct("TERM-M13-D", "Priced, unbanded", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        assertThrows(InvalidProductVersionException.class, () ->
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
                null,
                List.of(new ProductApi.BaseRateInput(18, 25, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("15.2000"))),
                "actuary@nlolo.co.tz"));
    }

    /**
     * The pre-pricing path is untouched: a version with no base rates publishes on
     * the old rules and is simply unpriceable. 45 existing call sites depend on
     * this, and GROUP_LIFE -- rated on scheme size -- cannot populate an
     * (age band, sex, smoker) key at all.
     */
    @Test
    void publishVersionWithoutBaseRatesKeepsTheOriginalRules() {
        ProductSummaryView product = productApi.createProduct("GRP-M13-E", "Group life, scheme-rated", ProductCategory.GROUP_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "18-65", BigDecimal.ONE, 18, 65),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary@nlolo.co.tz");

        UUID versionId = productVersionRepository
            .findByTenantIdAndProductIdAndActiveForNewBusinessTrue(TenantContext.get(), product.productId())
            .get(0).getProductVersionId();
        assertTrue(baseRateRepository.findByProductVersionId(versionId).isEmpty());
        assertFalse(baseRateRepository.existsByProductVersionId(versionId));
    }

    /**
     * Two rows for one cell would make pricing depend on row order -- a silent
     * mispricing rather than an error.
     *
     * Caught by the OVERLAP validation, not by the unique constraint: two identical
     * bands are a degenerate overlap, so publish rejects them at the application
     * layer with a message naming the bands, before the database is reached. The
     * constraint remains as a backstop for any other writer -- proved separately in
     * `theUniqueConstraintBacksTheOverlapCheckAtTheDatabase`, because a constraint
     * nothing exercises is one a future migration can drop silently.
     */
    @Test
    void aDuplicateBaseRateCellIsRefusedAtPublish() {
        ProductSummaryView product = productApi.createProduct("TERM-M13-F", "Duplicated cell", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        InvalidProductVersionException ex = assertThrows(InvalidProductVersionException.class, () ->
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
                null,
                List.of(new ProductApi.BaseRateInput(18, 25, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("15.2000")),
                        new ProductApi.BaseRateInput(18, 25, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("99.9000"))),
                "actuary@nlolo.co.tz"));
        assertTrue(ex.getMessage().contains("overlap"));
    }

    /**
     * The unique constraint is unreachable through publishVersion now that the
     * overlap check precedes it, so it is exercised directly. Otherwise the guard
     * that protects every OTHER writer would have no test at all.
     */
    @Test
    void theUniqueConstraintBacksTheOverlapCheckAtTheDatabase() {
        UUID productId = pricedProduct("TERM-M13-H", new BigDecimal("15.2000"));
        UUID versionId = productVersionRepository
            .findByTenantIdAndProductIdAndActiveForNewBusinessTrue(TenantContext.get(), productId)
            .get(0).getProductVersionId();
        UUID tenantId = TenantContext.get();

        assertThrows(DataIntegrityViolationException.class, () ->
            baseRateRepository.saveAndFlush(new tz.co.nlolo.lifeplatform.product.domain.BaseRate(
                tenantId, versionId, 18, 25, "FEMALE", "NON_SMOKER", new BigDecimal("99.9000"))));
    }

    /**
     * A zero or negative rate is a free policy or one that pays the customer. The
     * table CHECK makes it unrepresentable at rest, independently of the
     * `@Positive` bean constraint at the HTTP edge -- which is not on this path.
     */
    @Test
    void aNonPositiveBaseRateIsRefusedByTheDatabase() {
        ProductSummaryView product = productApi.createProduct("TERM-M13-G", "Free cover", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        assertThrows(DataIntegrityViolationException.class, () ->
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
                null,
                List.of(new ProductApi.BaseRateInput(18, 25, Sex.FEMALE, SmokerStatus.NON_SMOKER, BigDecimal.ZERO)),
                "actuary@nlolo.co.tz"));
    }

    /**
     * The same silent-mispricing shape as a duplicate base rate cell, one table over, and it went
     * unnoticed far longer: {@code rating_table} carried only a NON-unique index, so a version could
     * hold two rows for one (factorType, band) with different multipliers, and both readers
     * ({@code strictMultiplier} for quoting, {@code resolveRatingMultiplier} for underwriting) filter
     * to the band and then take {@code findFirst()}. Which multiplier applied depended on row order.
     *
     * <p>Reachable through the authoring form, which lets a user add the same band twice -- so this
     * was a real path, not a direct-insert-only concern. Found by sweeping for the defect class
     * rather than by a failure.
     */
    @Test
    void aDuplicateRatingFactorBandIsRefusedAtPublish() {
        ProductSummaryView product = productApi.createProduct("TERM-M13-I", "Duplicated band",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        InvalidProductVersionException ex = assertThrows(InvalidProductVersionException.class, () ->
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                        new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", new BigDecimal("2.5000"), 30, 39),
                        new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
                null, "actuary@nlolo.co.tz"));
        // The message must name the offending band -- a bare constraint violation would leave an
        // actuary to find which of forty rows was the duplicate.
        assertTrue(ex.getMessage().contains("30-39"));
        assertTrue(ex.getMessage().contains("AGE"));
    }

    /**
     * As with base rates, the application check now precedes the constraint, so the constraint is
     * unreachable through {@code publishVersion} and is exercised directly. A backstop nothing tests
     * is one a future migration drops without anyone noticing.
     */
    @Test
    void theUniqueBandConstraintBacksTheDuplicateCheckAtTheDatabase() {
        ProductSummaryView product = productApi.createProduct("TERM-M13-J", "Band constraint",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary@nlolo.co.tz");

        UUID tenantId = TenantContext.get();
        UUID versionId = productVersionRepository
            .findByTenantIdAndProductIdAndActiveForNewBusinessTrue(tenantId, product.productId())
            .get(0).getProductVersionId();

        assertThrows(DataIntegrityViolationException.class, () ->
            ratingFactorRepository.saveAndFlush(new tz.co.nlolo.lifeplatform.product.domain.RatingFactor(
                tenantId, versionId, FactorType.AGE.name(), "30-39", new BigDecimal("2.5000"))));
    }

    // ---- AGE rating factors carry real bounds ----------------------------------

    private ProductSummaryView ageProduct(String code) {
        return productApi.createProduct(code, code, ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
    }

    /**
     * The shape rule. Age is rated by range now, so an AGE row without one is unusable —
     * it would match nobody and silently contribute the neutral 1.0, which is exactly the
     * bug this whole change removes.
     */
    @Test
    void publishVersionRejectsAnAgeFactorWithNoRange() {
        ProductSummaryView product = ageProduct("TERM-AGE-A");
        InvalidProductVersionException ex = assertThrows(InvalidProductVersionException.class, () ->
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE),
                        new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
                null, "actuary@nlolo.co.tz"));
        assertTrue(ex.getMessage().contains("30-39"), "the message must name the offending band");
    }

    @Test
    void publishVersionRejectsOverlappingAgeRanges() {
        ProductSummaryView product = ageProduct("TERM-AGE-B");
        InvalidProductVersionException ex = assertThrows(InvalidProductVersionException.class, () ->
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                // A 25-year-old falls in both. Which multiplier applies would be scan order.
                List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "18-30", BigDecimal.ONE, 18, 30),
                        new ProductApi.RatingFactorInput(FactorType.AGE, "25-40", new BigDecimal("2.0"), 25, 40),
                        new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
                null, "actuary@nlolo.co.tz"));
        assertTrue(ex.getMessage().contains("overlap"));
    }

    @Test
    void publishVersionRejectsAnImpossibleAgeRange() {
        ProductSummaryView product = ageProduct("TERM-AGE-C");
        assertThrows(InvalidProductVersionException.class, () ->
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "40-30", BigDecimal.ONE, 40, 30),
                        new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
                null, "actuary@nlolo.co.tz"));
    }

    @Test
    void resolveAgeMultiplierPicksTheCoveringBandAndIsNeutralOutsideThem() {
        ProductSummaryView product = ageProduct("TERM-AGE-D");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "18-39", BigDecimal.ONE, 18, 39),
                    new ProductApi.RatingFactorInput(FactorType.AGE, "60-99", new BigDecimal("3.0"), 60, 99),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary@nlolo.co.tz");
        UUID versionId = productVersionRepository
            .findByTenantIdAndProductIdAndActiveForNewBusinessTrue(TenantContext.get(), product.productId())
            .get(0).getProductVersionId();

        assertEquals(0, BigDecimal.ONE.compareTo(productApi.resolveAgeMultiplier(versionId, 25)));
        // Inclusive at both ends -- 39 and 60 are inside their bands, not between them.
        assertEquals(0, BigDecimal.ONE.compareTo(productApi.resolveAgeMultiplier(versionId, 39)));
        assertEquals(0, new BigDecimal("3.0").compareTo(productApi.resolveAgeMultiplier(versionId, 60)));
        assertEquals(0, new BigDecimal("3.0").compareTo(productApi.resolveAgeMultiplier(versionId, 99)));
        // 40-59 is a gap this product simply does not rate: neutral, not an error.
        assertEquals(0, BigDecimal.ONE.compareTo(productApi.resolveAgeMultiplier(versionId, 45)));
    }

    /**
     * The database refuses an unbounded AGE row too, not just the application check — so a
     * writer that bypasses {@code publishVersion} cannot recreate the state where age looks
     * rated and silently is not.
     *
     * <p>NOT the same thing as the legacy rows already in long-lived databases. The
     * constraint is NOT VALID, so rows written before V5 survive untouched (the dev database
     * holds 99 of them, including the bare band "21" that is why bounds exist at all), and
     * those versions keep resolving to a neutral 1.0 because a NULL bound can never satisfy
     * {@code ageFrom <= :age}. That behaviour cannot be asserted here: NOT VALID still
     * enforces the constraint on every INSERT, so no supported path can construct a legacy
     * row inside a test. Reproducing it would mean dropping and re-adding the constraint
     * mid-test, which would be testing the test rather than the platform.
     */
    @Test
    void theDatabaseAlsoRefusesAnAgeRowWithNoBounds() {
        ProductSummaryView product = ageProduct("TERM-AGE-E");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "18-39", BigDecimal.ONE, 18, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary@nlolo.co.tz");
        UUID tenantId = TenantContext.get();
        UUID versionId = productVersionRepository
            .findByTenantIdAndProductIdAndActiveForNewBusinessTrue(tenantId, product.productId())
            .get(0).getProductVersionId();

        assertThrows(DataIntegrityViolationException.class, () ->
            ratingFactorRepository.saveAndFlush(new tz.co.nlolo.lifeplatform.product.domain.RatingFactor(
                tenantId, versionId, FactorType.AGE.name(), "no-bounds", new BigDecimal("9.0"))));
    }

    @Test
    void theDatabaseRefusesAgeBoundsOnANonAgeFactor() {
        // The other half of the shape rule: bounds on an OCCUPATION_CLASS row would be data
        // nothing reads, and a reader could reasonably assume it was rated on.
        ProductSummaryView product = ageProduct("TERM-AGE-F");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "18-39", BigDecimal.ONE, 18, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary@nlolo.co.tz");
        UUID tenantId = TenantContext.get();
        UUID versionId = productVersionRepository
            .findByTenantIdAndProductIdAndActiveForNewBusinessTrue(tenantId, product.productId())
            .get(0).getProductVersionId();

        assertThrows(DataIntegrityViolationException.class, () ->
            ratingFactorRepository.saveAndFlush(new tz.co.nlolo.lifeplatform.product.domain.RatingFactor(
                tenantId, versionId, FactorType.OCCUPATION_CLASS.name(), "CLASS_1", BigDecimal.ONE, 18, 39)));
    }

    // ---- M13 step 3: the premium calculation -----------------------------------

    /** A priced version: one band, one occupation class, one sum-assured band. */
    private UUID pricedProduct(String code, BigDecimal ratePerMille) {
        ProductSummaryView product = productApi.createProduct(code, code, ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now().minusDays(1), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE),
                    new ProductApi.RatingFactorInput(FactorType.OCCUPATION_CLASS, "CLASS_1", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null,
            List.of(new ProductApi.BaseRateInput(18, 25, Sex.FEMALE, SmokerStatus.NON_SMOKER, ratePerMille)),
            "actuary@nlolo.co.tz");
        return product.productId();
    }

    private ProductApi.PremiumQuoteInput quoteFor(UUID productId, LocalDate dateOfBirth, PremiumFrequency frequency) {
        return new ProductApi.PremiumQuoteInput(productId, new BigDecimal("10000000.00"), "TZS",
            dateOfBirth, Sex.FEMALE, SmokerStatus.NON_SMOKER, "CLASS_1", "LOW", frequency, LocalDate.now());
    }

    /**
     * Expected values computed BY HAND, not by re-running the implementation's own
     * arithmetic: TZS 10,000,000 / 1,000 = 10,000 units, x 15.2 = 152,000 annual,
     * / 12 = 12,666.666... -> 12,666.67, rounded HALF_UP once at the end.
     */
    @Test
    void quotePremiumComputesFromTheBaseRateAndReturnsItsDerivation() {
        UUID productId = pricedProduct("TERM-Q1", new BigDecimal("15.2000"));
        ProductApi.PremiumQuoteView quote =
            productApi.quotePremium(quoteFor(productId, LocalDate.now().minusYears(20), PremiumFrequency.MONTHLY));

        assertEquals(20, quote.ageAtEntry());
        assertEquals(18, quote.ageFrom());
        assertEquals(25, quote.ageTo());
        assertEquals(0, new BigDecimal("152000.00").compareTo(quote.annualBase()));
        assertEquals(0, new BigDecimal("12666.67").compareTo(quote.instalmentAmount()));
        assertEquals(12, quote.instalmentsPerYear());
        // The derivation is returned so no caller has to recompute it.
        assertEquals(2, quote.appliedFactors().size());
    }

    /** Rounding happens ONCE at the end; 152,000 / 4 and / 1 are exact. */
    @Test
    void quotePremiumDividesByTheFrequency() {
        UUID productId = pricedProduct("TERM-Q2", new BigDecimal("15.2000"));

        assertEquals(0, new BigDecimal("38000.00").compareTo(productApi
            .quotePremium(quoteFor(productId, LocalDate.now().minusYears(20), PremiumFrequency.QUARTERLY))
            .instalmentAmount()));
        assertEquals(0, new BigDecimal("152000.00").compareTo(productApi
            .quotePremium(quoteFor(productId, LocalDate.now().minusYears(20), PremiumFrequency.ANNUALLY))
            .instalmentAmount()));
    }

    /**
     * A multiplier genuinely multiplies: 10,000 units x 20.0 = 200,000 annual, then
     * x 1.25 occupation loading = 250,000, / 12 = 20,833.333... -> 20,833.33.
     */
    @Test
    void quotePremiumAppliesMultipliersOnTopOfTheBaseRate() {
        ProductSummaryView product = productApi.createProduct("TERM-Q3", "Loaded", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now().minusDays(1), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE),
                    new ProductApi.RatingFactorInput(FactorType.OCCUPATION_CLASS, "CLASS_3", new BigDecimal("1.25"))),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null,
            List.of(new ProductApi.BaseRateInput(18, 25, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("20.0000"))),
            "actuary@nlolo.co.tz");

        ProductApi.PremiumQuoteView quote = productApi.quotePremium(new ProductApi.PremiumQuoteInput(
            product.productId(), new BigDecimal("10000000.00"), "TZS", LocalDate.now().minusYears(20),
            Sex.FEMALE, SmokerStatus.NON_SMOKER, "CLASS_3", "LOW", PremiumFrequency.MONTHLY, LocalDate.now()));

        assertEquals(0, new BigDecimal("200000.00").compareTo(quote.annualBase()));
        assertEquals(0, new BigDecimal("250000.00").compareTo(quote.annualAfterFactors()));
        assertEquals(0, new BigDecimal("20833.33").compareTo(quote.instalmentAmount()));
    }

    /**
     * Strict resolution, one assertion per dimension. This is the fallback hazard:
     * resolveRatingMultiplier returns a neutral 1.0 on no match, which on a premium
     * would price a real contract as if the factor did not apply.
     */
    @Test
    void quotePremiumRefusesRatherThanFallingBackOnAnyMissingDimension() {
        UUID productId = pricedProduct("TERM-Q4", new BigDecimal("15.2000"));

        // age outside every band
        assertThrows(PremiumNotQuotableException.class, () ->
            productApi.quotePremium(quoteFor(productId, LocalDate.now().minusYears(40), PremiumFrequency.MONTHLY)));
        // sex with no cell
        assertThrows(PremiumNotQuotableException.class, () -> productApi.quotePremium(new ProductApi.PremiumQuoteInput(
            productId, new BigDecimal("10000000.00"), "TZS", LocalDate.now().minusYears(20),
            Sex.MALE, SmokerStatus.NON_SMOKER, "CLASS_1", "LOW", PremiumFrequency.MONTHLY, LocalDate.now())));
        // smoker status with no cell
        assertThrows(PremiumNotQuotableException.class, () -> productApi.quotePremium(new ProductApi.PremiumQuoteInput(
            productId, new BigDecimal("10000000.00"), "TZS", LocalDate.now().minusYears(20),
            Sex.FEMALE, SmokerStatus.SMOKER, "CLASS_1", "LOW", PremiumFrequency.MONTHLY, LocalDate.now())));
        // occupation class with no multiplier
        assertThrows(PremiumNotQuotableException.class, () -> productApi.quotePremium(new ProductApi.PremiumQuoteInput(
            productId, new BigDecimal("10000000.00"), "TZS", LocalDate.now().minusYears(20),
            Sex.FEMALE, SmokerStatus.NON_SMOKER, "CLASS_9", "LOW", PremiumFrequency.MONTHLY, LocalDate.now())));
        // sum-assured band with no multiplier
        assertThrows(PremiumNotQuotableException.class, () -> productApi.quotePremium(new ProductApi.PremiumQuoteInput(
            productId, new BigDecimal("10000000.00"), "TZS", LocalDate.now().minusYears(20),
            Sex.FEMALE, SmokerStatus.NON_SMOKER, "CLASS_1", "HIGH", PremiumFrequency.MONTHLY, LocalDate.now())));
    }

    /** An unpriced version is a real state, and says so instead of guessing. */
    @Test
    void quotePremiumRefusesAVersionWithNoBaseRates() {
        ProductSummaryView product = productApi.createProduct("TERM-Q5", "Unpriced", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now().minusDays(1), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "18-25", BigDecimal.ONE, 18, 25),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary@nlolo.co.tz");

        PremiumNotQuotableException ex = assertThrows(PremiumNotQuotableException.class, () ->
            productApi.quotePremium(quoteFor(product.productId(), LocalDate.now().minusYears(20), PremiumFrequency.MONTHLY)));
        assertTrue(ex.getMessage().contains("no base rate table"));
    }

    /** Age is derived from the date of birth, never taken from the caller. */
    @Test
    void quotePremiumDerivesEntryAgeAndRespectsTheBandBoundary() {
        UUID productId = pricedProduct("TERM-Q6", new BigDecimal("15.2000"));

        // Exactly 25 today: inside, because ageTo is inclusive.
        assertEquals(25, productApi
            .quotePremium(quoteFor(productId, LocalDate.now().minusYears(25), PremiumFrequency.MONTHLY))
            .ageAtEntry());
        // A day short of 18 is still 17, and outside the band.
        assertThrows(PremiumNotQuotableException.class, () -> productApi.quotePremium(
            quoteFor(productId, LocalDate.now().minusYears(18).plusDays(1), PremiumFrequency.MONTHLY)));
    }

    /** Overlapping bands would make the premium depend on row order. */
    @Test
    void publishVersionRejectsOverlappingAgeBands() {
        ProductSummaryView product = productApi.createProduct("TERM-Q7", "Overlapping", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        InvalidProductVersionException ex = assertThrows(InvalidProductVersionException.class, () ->
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
                null,
                List.of(new ProductApi.BaseRateInput(18, 25, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("15.2")),
                        new ProductApi.BaseRateInput(20, 30, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("17.4"))),
                "actuary@nlolo.co.tz"));
        assertTrue(ex.getMessage().contains("overlap"));
    }

    /** Adjacent bands are fine -- 18-25 and 26-30 do not overlap. */
    @Test
    void publishVersionAcceptsAdjacentAgeBands() {
        ProductSummaryView product = productApi.createProduct("TERM-Q8", "Adjacent", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null,
            List.of(new ProductApi.BaseRateInput(18, 25, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("15.2")),
                    new ProductApi.BaseRateInput(26, 30, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("17.4"))),
            "actuary@nlolo.co.tz");

        UUID versionId = productVersionRepository
            .findByTenantIdAndProductIdAndActiveForNewBusinessTrue(TenantContext.get(), product.productId())
            .get(0).getProductVersionId();
        assertEquals(2, baseRateRepository.findByProductVersionId(versionId).size());
    }

    /** Same band, different sex: not an overlap. */
    @Test
    void publishVersionAllowsTheSameBandForADifferentCell() {
        ProductSummaryView product = productApi.createProduct("TERM-Q9", "Both sexes", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null,
            List.of(new ProductApi.BaseRateInput(18, 25, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("15.2")),
                    new ProductApi.BaseRateInput(18, 25, Sex.MALE, SmokerStatus.NON_SMOKER, new BigDecimal("16.8"))),
            "actuary@nlolo.co.tz");

        UUID versionId = productVersionRepository
            .findByTenantIdAndProductIdAndActiveForNewBusinessTrue(TenantContext.get(), product.productId())
            .get(0).getProductVersionId();
        assertEquals(2, baseRateRepository.findByProductVersionId(versionId).size());
    }

    /** The rating read-back, for actuarial review. */
    @Test
    void getVersionRatingReturnsTheBasisWithoutTouchingProductSnapshot() {
        UUID productId = pricedProduct("TERM-Q10", new BigDecimal("15.2000"));
        UUID versionId = productVersionRepository
            .findByTenantIdAndProductIdAndActiveForNewBusinessTrue(TenantContext.get(), productId)
            .get(0).getProductVersionId();

        ProductApi.VersionRatingView rating = productApi.getVersionRating(productId, versionId);
        assertEquals(1, rating.baseRates().size());
        assertEquals(2, rating.ratingFactors().size());
        assertEquals(1, rating.benefitSchedule().size());
        assertEquals(0, new BigDecimal("15.2000").compareTo(rating.baseRates().get(0).ratePerMille()));
    }

    @Test
    void getVersionRatingIsTenantIsolated() {
        UUID productId = pricedProduct("TERM-Q11", new BigDecimal("15.2000"));
        UUID versionId = productVersionRepository
            .findByTenantIdAndProductIdAndActiveForNewBusinessTrue(TenantContext.get(), productId)
            .get(0).getProductVersionId();

        TenantContext.set(UUID.randomUUID());
        assertThrows(ProductNotFoundException.class, () -> productApi.getVersionRating(productId, versionId));
    }

    @Test
    void publishVersionRejectsIncompleteRatingFactorCoverage() {
        ProductSummaryView product = productApi.createProduct("TERM-05", "Term missing coverage", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        assertThrows(InvalidProductVersionException.class, () ->
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39)), // missing SUM_ASSURED_BAND
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
                null, "actuary@nlolo.co.tz"));
    }

    @Test
    void getActiveSnapshotReturnsPublishedVersion() {
        ProductSummaryView product = productApi.createProduct("TERM-06", "Snapshot test", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.GMM, LocalDate.now().minusDays(1), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary@nlolo.co.tz");

        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        assertEquals(IfrsMeasurementModel.GMM, snapshot.ifrsMeasurementModel());
    }

    @Test
    void resolveRatingMultiplierReturnsNeutralWhenBandNotFound() {
        ProductSummaryView product = productApi.createProduct("TERM-07", "Multiplier test", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.GMM, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", new BigDecimal("1.5"), 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary@nlolo.co.tz");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());

        assertEquals(0, new BigDecimal("1.5").compareTo(productApi.resolveRatingMultiplier(snapshot.productVersionId(), FactorType.AGE, "30-39")));
        assertEquals(0, BigDecimal.ONE.compareTo(productApi.resolveRatingMultiplier(snapshot.productVersionId(), FactorType.AGE, "NO-SUCH-BAND")));
    }

    @Test
    void productsAreTenantIsolated() {
        ProductSummaryView product = productApi.createProduct("SHARED-CODE", "Tenant A product", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary@nlolo.co.tz");

        // Prove the negative result under tenant B isn't just "nothing is ever active": the
        // creating tenant (A) must see its own now-ACTIVE product in its own listing.
        List<ProductSummaryView> tenantAProducts = productApi.listActiveProducts(null);
        assertTrue(tenantAProducts.stream().anyMatch(p -> p.productCode().equals("SHARED-CODE") && p.status() == ProductStatus.ACTIVE));

        TenantContext.clear();
        TenantContext.set(UUID.randomUUID());
        List<ProductSummaryView> tenantBProducts = productApi.listActiveProducts(null);
        assertTrue(tenantBProducts.stream().noneMatch(p -> p.productCode().equals("SHARED-CODE")));
    }

    @Test
    void publishingSecondVersionRetiresFirstFromNewBusinessAndBecomesActiveSnapshot() {
        // Regression test for the final-review C1 finding: ux_product_version_active is a
        // partial unique index permitting at most one is_active_for_new_business = true row
        // per product_id. Before the fix, this second publishVersion call raised a raw
        // DataIntegrityViolationException (surfaced to callers as an uncaught 500).
        ProductSummaryView product = productApi.createProduct("TERM-08", "Rollover test", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");

        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now().minusDays(2), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary@nlolo.co.tz");
        ProductSnapshotView firstSnapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        UUID firstVersionId = firstSnapshot.productVersionId();

        // Second publish on the same product must succeed, not crash.
        assertDoesNotThrow(() -> productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", new BigDecimal("1.25"), 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary@nlolo.co.tz"));

        List<ProductVersion> versions = productVersionRepository.findByTenantIdAndProductIdOrderByEffectiveDateDesc(TenantContext.get(), product.productId());
        assertEquals(2, versions.size());
        ProductVersion olderVersion = versions.stream().filter(v -> v.getProductVersionId().equals(firstVersionId)).findFirst().orElseThrow();
        ProductVersion newerVersion = versions.stream().filter(v -> !v.getProductVersionId().equals(firstVersionId)).findFirst().orElseThrow();
        assertFalse(olderVersion.isActiveForNewBusiness(), "older version must be retired from new business");
        assertTrue(newerVersion.isActiveForNewBusiness(), "newly published version must be active for new business");

        ProductSnapshotView latestSnapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        assertEquals(newerVersion.getProductVersionId(), latestSnapshot.productVersionId(), "getActiveSnapshot must return the newer version");
    }

    @Test
    void createProductRejectsDuplicateProductCodeForSameTenant() {
        productApi.createProduct("DUP-01", "First product", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        assertThrows(DuplicateProductCodeException.class, () ->
            productApi.createProduct("DUP-01", "Second product with same code", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz"));
    }
}
