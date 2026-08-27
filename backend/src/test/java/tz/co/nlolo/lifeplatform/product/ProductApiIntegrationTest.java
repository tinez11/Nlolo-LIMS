package tz.co.nlolo.lifeplatform.product;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.product.api.*;
import tz.co.nlolo.lifeplatform.product.domain.ProductVersion;
import tz.co.nlolo.lifeplatform.product.infrastructure.BaseRateRepository;
import tz.co.nlolo.lifeplatform.product.infrastructure.ProductVersionRepository;
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
            "db-migrations/product/V2__base_rate_table.sql");
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
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE),
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
                List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE),
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
                List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE),
                        new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
                null,
                List.of(new ProductApi.BaseRateInput("30-39", Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("17.4000"))),
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
                List.of(new ProductApi.BaseRateInput("30-39", Sex.MALE, SmokerStatus.SMOKER, new BigDecimal("22.1000"))),
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
            List.of(new ProductApi.BaseRateInput("18-25", Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("15.2000")),
                    new ProductApi.BaseRateInput("18-25", Sex.MALE, SmokerStatus.NON_SMOKER, new BigDecimal("16.8000"))),
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
                List.of(new ProductApi.BaseRateInput("18-25", Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("15.2000"))),
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
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "18-65", BigDecimal.ONE),
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
     * mispricing rather than an error. The unique index is the guard.
     */
    @Test
    void aDuplicateBaseRateCellIsRefusedByTheDatabase() {
        ProductSummaryView product = productApi.createProduct("TERM-M13-F", "Duplicated cell", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        assertThrows(DataIntegrityViolationException.class, () ->
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
                null,
                List.of(new ProductApi.BaseRateInput("18-25", Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("15.2000")),
                        new ProductApi.BaseRateInput("18-25", Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("99.9000"))),
                "actuary@nlolo.co.tz"));
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
                List.of(new ProductApi.BaseRateInput("18-25", Sex.FEMALE, SmokerStatus.NON_SMOKER, BigDecimal.ZERO)),
                "actuary@nlolo.co.tz"));
    }

    @Test
    void publishVersionRejectsIncompleteRatingFactorCoverage() {
        ProductSummaryView product = productApi.createProduct("TERM-05", "Term missing coverage", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        assertThrows(InvalidProductVersionException.class, () ->
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE)), // missing SUM_ASSURED_BAND
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
                null, "actuary@nlolo.co.tz"));
    }

    @Test
    void getActiveSnapshotReturnsPublishedVersion() {
        ProductSummaryView product = productApi.createProduct("TERM-06", "Snapshot test", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.GMM, LocalDate.now().minusDays(1), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE),
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
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", new BigDecimal("1.5")),
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
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE),
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
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary@nlolo.co.tz");
        ProductSnapshotView firstSnapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        UUID firstVersionId = firstSnapshot.productVersionId();

        // Second publish on the same product must succeed, not crash.
        assertDoesNotThrow(() -> productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", new BigDecimal("1.25")),
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
