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

import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.product.application.ProductApiImpl;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;

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
            "db-migrations/product/V5__rating_table_age_bounds.sql",
            "db-migrations/product/V6__eligibility_bounds.sql",
            // V7 is the RLS fail-closed pass, which no test class lists. V8 IS listed here,
            // because theMultiplierConstraintBacksTheZeroCheckAtTheDatabase exercises the
            // constraint it adds -- a backstop nothing tests is one a future migration drops
            // without anyone noticing.
            "db-migrations/product/V8__rating_table_multiplier_positive.sql",
            "db-migrations/product/V9__rating_table_sum_assured_bounds.sql",
            "db-migrations/product/V10__ifrs_measurement_model_on_version.sql",
            "db-migrations/product/V11__frequency_loading.sql",
            "db-migrations/product/V12__tira_filing.sql",
            "db-migrations/product/V13__benefit_calculation_method.sql",
            // V14 widens product_definition_category_check to admit CREDIT_LIFE.
            // aCreditLifeProductCanBeCreatedAndReadBack fails without it -- and fails
            // as "duplicate product code", because createProduct reports every
            // DataIntegrityViolationException that way. See the note on that test.
            "db-migrations/product/V14__credit_life_category.sql",
            "db-migrations/product/V15__exclusion_periods.sql",
            "db-migrations/product/V16__base_rate_term_bands.sql",
            "db-migrations/product/V17__cash_value.sql",
            "db-migrations/product/V18__payout_schedule.sql",
            "db-migrations/benefitpayout/V1__create_benefitpayout_schema.sql");
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

    /** Only for reproducing a pre-V12 row, which the API can no longer produce. */
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    // ---- Credit life: §4 of the client's underwriting requirements table ----

    /**
     * The Java enum and product_definition_category_check are two copies of one list, and
     * ProductCategoryMigrationTest can only assert the Java half. This asserts the other
     * half against real Postgres: without V14 the row is rejected by the CHECK, and the
     * migration merely parsing would prove nothing.
     */
    @Test
    void aCreditLifeProductCanBeCreatedAndReadBack() {
        ProductSummaryView product = productApi.createProduct("CREDIT-LIFE-01",
            "Credit life", ProductCategory.CREDIT_LIFE, "TZS", "actuary@nlolo.co.tz");

        assertEquals(ProductCategory.CREDIT_LIFE, product.category());
        assertEquals(ProductCategory.CREDIT_LIFE,
            productApi.getProduct(product.productId()).category());
        assertEquals("CREDIT_LIFE", jdbcTemplate.queryForObject(
            "SELECT category FROM product.product_definition WHERE product_id = ?",
            String.class, product.productId()));
    }

    // ---- Batch 2b: a version says what it covers, and what each benefit pays ----

    @Test
    void publishVersionRefusesAVersionThatCoversNothing() {
        ProductSummaryView product = productApi.createProduct("TERM-B2B-NOBEN", "Covers nothing",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");

        InvalidProductVersionException thrown = assertThrows(InvalidProductVersionException.class, () ->
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                        new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(),
                null, ANY_FILING, "actuary@nlolo.co.tz"));

        assertThat(thrown.getMessage()).contains("at least one benefit");
    }

    @Test
    void aPercentageBenefitRoundTripsOntoTheRatingRead() {
        ProductSummaryView product = productApi.createProduct("TERM-B2B-PCT", "With a CI rider",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED),
                    new ProductApi.BenefitInput(BenefitType.CRITICAL_ILLNESS,
                        BenefitCalculationMethod.PERCENTAGE_OF_SUM_ASSURED, new BigDecimal("25.00"), null)),
            null, ANY_FILING, "actuary@nlolo.co.tz");
        UUID versionId = productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId();

        assertThat(productApi.resolveBenefitSchedule(versionId))
            .filteredOn(b -> b.benefitType() == BenefitType.CRITICAL_ILLNESS)
            .singleElement()
            .satisfies(ci -> {
                assertThat(ci.calculationMethod()).isEqualTo(BenefitCalculationMethod.PERCENTAGE_OF_SUM_ASSURED);
                assertThat(ci.amountFor(new BigDecimal("10000000")))
                    .isEqualByComparingTo(new BigDecimal("2500000.00"));
            });
    }

    /**
     * The shape rule refuses the publish, and it is the APPLICATION that refuses it.
     *
     * <p>Until publishVersion built a {@link BenefitDefinition}, the only check on the write path
     * was {@code benefit_schedule_amount_shape} at the database — so an author who declared a
     * percentage benefit and gave no percentage got a DataIntegrityViolationException, which is a
     * 500 and names nothing they could act on. Each case below is a malformed benefit an actuary
     * can plausibly author, and each must come back as a refusal that says what is wrong.
     */
    @Test
    void aBenefitCarryingTheWrongAmountIsRefusedAtPublish() {
        ProductSummaryView product = productApi.createProduct("TERM-B2B-SHAPE", "Bad shapes",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");

        record Case(String name, ProductApi.BenefitInput benefit, String expected) {}
        List<Case> cases = List.of(
            new Case("a percentage benefit with no percentage",
                new ProductApi.BenefitInput(BenefitType.CRITICAL_ILLNESS,
                    BenefitCalculationMethod.PERCENTAGE_OF_SUM_ASSURED, null, null),
                "needs a percentage"),
            new Case("a flat benefit with no amount",
                new ProductApi.BenefitInput(BenefitType.DISABILITY,
                    BenefitCalculationMethod.FLAT_AMOUNT, null, null),
                "needs a flat amount"),
            new Case("a whole-cover benefit carrying an amount it does not use",
                new ProductApi.BenefitInput(BenefitType.DEATH,
                    BenefitCalculationMethod.SUM_ASSURED, null, new BigDecimal("500000")),
                "neither a percentage nor a flat amount"),
            new Case("a percentage over 100",
                new ProductApi.BenefitInput(BenefitType.CRITICAL_ILLNESS,
                    BenefitCalculationMethod.PERCENTAGE_OF_SUM_ASSURED, new BigDecimal("150"), null),
                "no more than 100"),
            // A benefit that pays nothing is not a benefit -- and zero is what an empty numeric
            // field coerces to, so it is the shape a form is most likely to send.
            new Case("a flat benefit paying zero",
                new ProductApi.BenefitInput(BenefitType.DISABILITY,
                    BenefitCalculationMethod.FLAT_AMOUNT, null, BigDecimal.ZERO),
                "greater than zero"));

        for (Case c : cases) {
            InvalidProductVersionException thrown = assertThrows(InvalidProductVersionException.class, () ->
                productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                    List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                            new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                    List.of(c.benefit()),
                    null, ANY_FILING, "actuary@nlolo.co.tz"),
                c.name() + " should be refused at publish");
            assertThat(thrown.getMessage()).as(c.name()).contains(c.expected());
        }
    }

    // ---- Batch 3: the TIRA filing that authorises a version ---------------------

    @Test
    void publishVersionRefusesAVersionWithNoTiraFiling() {
        ProductSummaryView product = productApi.createProduct("TERM-B3-NOFILE", "Unfiled",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");

        InvalidProductVersionException thrown = assertThrows(InvalidProductVersionException.class, () ->
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                        new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
                null, null, "actuary@nlolo.co.tz"));

        assertThat(thrown.getMessage()).contains("TIRA filing");
    }

    @Test
    void aPublishedVersionCarriesItsTiraFiling() {
        ProductSummaryView product = productApi.createProduct("TERM-B3-FILED", "Filed",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, new TiraFiling("TIRA/LIFE/2026/0099", LocalDate.of(2026, 2, 1)), "actuary@nlolo.co.tz");
        UUID versionId = productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId();

        TiraFiling filing = productVersionRepository.findById(versionId).orElseThrow().getTiraFiling();
        assertThat(filing.reference()).isEqualTo("TIRA/LIFE/2026/0099");
        assertThat(filing.approvalDate()).isEqualTo(LocalDate.of(2026, 2, 1));
    }

    /**
     * A filing cannot be STRIPPED from a version once recorded, and that is worth asserting
     * because it was not the intent -- it is a property of {@code NOT VALID}.
     *
     * <p>{@code NOT VALID} declines to check rows that already exist when the constraint is
     * added; it enforces on every INSERT **and UPDATE** from then on. So the 133 pre-V12 rows
     * keep their nulls, and nothing can retroactively null a filing that was recorded. Found by
     * trying to build a grandfathered row for the test below and being refused by the database.
     */
    @Test
    void aRecordedTiraFilingCannotBeRemovedAfterwards() {
        ProductSummaryView product = productApi.createProduct("TERM-B3-STRIP", "Cannot be unfiled",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary@nlolo.co.tz");
        UUID versionId = productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId();

        assertThrows(DataIntegrityViolationException.class, () ->
            jdbcTemplate.update("UPDATE product.product_version SET tira_filing_reference = NULL,"
                + " tira_approval_date = NULL WHERE product_version_id = ?", versionId));
    }

    /**
     * A version published before V12 reads back null rather than an empty filing.
     *
     * <p>Asserted on the entity directly and without a database, because that state is no longer
     * reachable through either: no publish can omit the filing, and the {@code NOT VALID} check
     * refuses an UPDATE that nulls one (see above). The 133 rows that look like this predate the
     * constraint, and a fresh Testcontainers schema has none of them.
     */
    @Test
    void aVersionWithNoFilingColumnsReadsBackNull() {
        ProductVersion unfiled = new ProductVersion(UUID.randomUUID(), UUID.randomUUID(),
            LocalDate.now(), null, 30, null, "PAA", "actuary@nlolo.co.tz");

        assertThat(unfiled.getTiraFiling())
            .as("null, not an empty TiraFiling -- there is no such thing as a filing that is"
                + " present and empty, and the record's constructor would refuse to build one")
            .isNull();
    }

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

    /**
     * The other half of the rule above, and the reason it needed one.
     *
     * <p>Excluding a DRAFT from the catalogue is right. Excluding it from EVERYTHING was not:
     * {@code listActiveProducts} was the only listing on the platform, so a product whose
     * second authoring phase was abandoned appeared nowhere at all, while
     * {@code ux_product_code} went on holding its code. Reported from the console as "a
     * product with code Education02 already exists but is not on the list" — the code was
     * burned and the product could be neither seen nor finished.
     */
    @Test
    void draftProductIsReachableThroughTheDraftListing() {
        ProductSummaryView draft = productApi.createProduct(
            "TERM-DRAFT-01", "Abandoned Term Life", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");

        List<ProductSummaryView> drafts = productApi.listDraftProducts();
        assertTrue(drafts.stream().anyMatch(p ->
            p.productCode().equals("TERM-DRAFT-01")
                && p.productId().equals(draft.productId())
                && p.status() == ProductStatus.DRAFT),
            "a created-but-unpublished product must be reachable somewhere");
    }

    /** Publishing is what finishes the job, so the draft list must let go of it. */
    @Test
    void publishingRemovesAProductFromTheDraftListing() {
        ProductSummaryView product = productApi.createProduct(
            "TERM-DRAFT-02", "Finished Term Life", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        assertTrue(productApi.listDraftProducts().stream().anyMatch(p -> p.productCode().equals("TERM-DRAFT-02")));

        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary@nlolo.co.tz");

        assertTrue(productApi.listDraftProducts().stream().noneMatch(p -> p.productCode().equals("TERM-DRAFT-02")),
            "a published product is no longer an unfinished authoring task");
        assertTrue(productApi.listActiveProducts(null).stream().anyMatch(p -> p.productCode().equals("TERM-DRAFT-02")));
    }

    /**
     * A product id resolves to a NAME.
     *
     * <p>Nothing on the platform did this. {@code getActiveSnapshot} takes an id and returns
     * pricing; the catalogue carries names but is keyed by nothing. So every screen holding
     * an id it had not itself picked from a list printed the raw uuid — the underwriting
     * queue rendered a whole column of them.
     */
    @Test
    void productIsResolvableByIdToItsCodeAndName() {
        ProductSummaryView created = productApi.createProduct(
            "TERM-BYID-01", "Resolvable Term Life", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");

        ProductSummaryView found = productApi.getProduct(created.productId());

        assertEquals("TERM-BYID-01", found.productCode());
        assertEquals("Resolvable Term Life", found.productName());
        assertEquals(ProductCategory.TERM_LIFE, found.category());
        assertEquals(created.productId(), found.productId());
    }

    /**
     * Any status, and a DRAFT is the cheap proof of it.
     *
     * <p>The reason is a RETIRED product, which cannot be created directly here: it is gone
     * from the catalogue while the policies and cases referencing it are still on screen, so
     * an ACTIVE-only lookup would print a uuid on exactly the records whose history someone
     * is reading. A DRAFT exercises the same code path -- neither status is in the catalogue.
     */
    @Test
    void productOutsideTheCatalogueIsStillResolvableById() {
        ProductSummaryView draft = productApi.createProduct(
            "TERM-BYID-02", "Unlaunched Term Life", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        assertTrue(productApi.listActiveProducts(null).stream()
            .noneMatch(p -> p.productId().equals(draft.productId())), "a DRAFT is not in the catalogue");

        assertEquals("Unlaunched Term Life", productApi.getProduct(draft.productId()).productName());
    }

    @Test
    void unknownProductIdIsNotFoundRatherThanNull() {
        assertThrows(ProductNotFoundException.class, () -> productApi.getProduct(UUID.randomUUID()));
    }

    @Test
    void publishVersionActivatesProductAndAppearsInListing() {
        ProductSummaryView product = productApi.createProduct("TERM-03", "Published Term Life", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary@nlolo.co.tz");

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
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
                List.of(new ProductApi.FundInput("FUND-A", BigDecimal.TEN)),
                ANY_FILING, "actuary@nlolo.co.tz"));
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
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
                null,
                List.of(new ProductApi.BaseRateInput(30, 39, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("17.4000"))),
                ANY_FILING, "actuary@nlolo.co.tz"));
    }

    @Test
    void publishVersionRejectsSmokerMultiplierAlongsideBaseRates() {
        ProductSummaryView product = productApi.createProduct("TERM-M13-B", "Double-counted smoker", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        assertThrows(InvalidProductVersionException.class, () ->
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.SMOKER_STATUS, "SMOKER", new BigDecimal("1.5")),
                        new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
                null,
                List.of(new ProductApi.BaseRateInput(30, 39, Sex.MALE, SmokerStatus.SMOKER, new BigDecimal("22.1000"))),
                ANY_FILING, "actuary@nlolo.co.tz"));
    }

    // ---- Batch 1: a priced version must be able to price what it accepts -------

    @Test
    void publishVersionRejectsAPricedVersionThatDoesNotSayWhatAgesItSellsTo() {
        ProductSummaryView product = productApi.createProduct("TERM-B1-R1", "Priced but unbounded",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");

        InvalidProductVersionException thrown = assertThrows(InvalidProductVersionException.class, () ->
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
                null,
                List.of(new ProductApi.BaseRateInput(18, 65, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("1.5000")),
                        new ProductApi.BaseRateInput(18, 65, Sex.MALE, SmokerStatus.NON_SMOKER, new BigDecimal("2.0000"))),
                ANY_FILING, "actuary@nlolo.co.tz"));

        assertThat(thrown.getMessage()).contains("entry age");
    }

    @Test
    void publishVersionStillAcceptsAnUnpricedVersionWithNoBoundsAtAll() {
        ProductSummaryView product = productApi.createProduct("TERM-B1-R1-OK", "Unpriced, unbounded",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");

        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null,
            ANY_FILING, "actuary@nlolo.co.tz");

        assertThat(productApi.listActiveProducts(ProductCategory.TERM_LIFE))
            .anyMatch(p -> p.productCode().equals("TERM-B1-R1-OK"));
    }

    /**
     * The shape of a real published version: women priced only from 56, men only to 56, on a
     * product declaring it accepts 18-78. Six cells, six distinct (sex, smoker) combinations and a
     * span of 18 to 78 in aggregate -- it looks complete and prices nobody in half its range.
     */
    @Test
    void publishVersionRejectsABaseRateTableWithAHoleInsideTheAgesItAccepts() {
        ProductSummaryView product = productApi.createProduct("TERM-B1-R2", "Coverage hole",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");

        InvalidProductVersionException thrown = assertThrows(InvalidProductVersionException.class, () ->
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
                null,
                List.of(new ProductApi.BaseRateInput(56, 78, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("2.1000")),
                        new ProductApi.BaseRateInput(18, 78, Sex.MALE, SmokerStatus.NON_SMOKER, new BigDecimal("1.7000"))),
                new EligibilityBounds(18, 78, null, null, null, null),
                ANY_FILING, "actuary@nlolo.co.tz"));

        assertThat(thrown.getMessage())
            .contains("FEMALE/NON_SMOKER")
            .contains("18-55")
            .contains("18-78");
    }

    /** A combination absent entirely covers nothing, which is the asymmetric-table case. */
    @Test
    void publishVersionRejectsASmokerStatusPricedForOneSexOnly() {
        ProductSummaryView product = productApi.createProduct("TERM-B1-R2B", "Asymmetric table",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");

        InvalidProductVersionException thrown = assertThrows(InvalidProductVersionException.class, () ->
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
                null,
                List.of(new ProductApi.BaseRateInput(18, 65, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("1.5000")),
                        new ProductApi.BaseRateInput(18, 65, Sex.MALE, SmokerStatus.NON_SMOKER, new BigDecimal("2.0000")),
                        new ProductApi.BaseRateInput(18, 65, Sex.FEMALE, SmokerStatus.SMOKER, new BigDecimal("3.0000"))),
                new EligibilityBounds(18, 65, null, null, null, null),
                ANY_FILING, "actuary@nlolo.co.tz"));

        assertThat(thrown.getMessage()).contains("MALE/SMOKER").contains("18-65");
    }

    /** Bands may run past the declared range; only holes inside it are faults. */
    @Test
    void publishVersionAcceptsATableThatCoversTheWholeDeclaredRange() {
        ProductSummaryView product = productApi.createProduct("TERM-B1-R2C", "Complete table",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");

        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null,
            List.of(new ProductApi.BaseRateInput(18, 45, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("1.6000")),
                    new ProductApi.BaseRateInput(46, 79, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("2.4000")),
                    new ProductApi.BaseRateInput(18, 79, Sex.MALE, SmokerStatus.NON_SMOKER, new BigDecimal("2.5000"))),
            new EligibilityBounds(18, 40, null, null, null, null),
            ANY_FILING, "actuary@nlolo.co.tz");

        assertThat(productApi.listActiveProducts(ProductCategory.TERM_LIFE))
            .anyMatch(p -> p.productCode().equals("TERM-B1-R2C"));
    }

    @Test
    void aPublishedVersionCarriesItsFrequencyLoadingAndDefaultsToUnloaded() {
        ProductSummaryView loaded = productApi.createProduct("TERM-B2A-LOAD", "Loaded",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(loaded.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, List.of(), EligibilityBounds.none(),
            new FrequencyLoading(new BigDecimal("8.00"), new BigDecimal("3.00")), ANY_FILING, "actuary@nlolo.co.tz");
        UUID loadedVersionId = productApi.getActiveSnapshot(loaded.productId(), LocalDate.now()).productVersionId();

        FrequencyLoading readBack = productApi.resolveFrequencyLoading(loadedVersionId);
        assertThat(readBack.monthlyPercent()).isEqualByComparingTo(new BigDecimal("8.00"));
        assertThat(readBack.quarterlyPercent()).isEqualByComparingTo(new BigDecimal("3.00"));

        // A version published through any older overload is unloaded, which is what keeps every
        // existing product pricing exactly as it does today.
        ProductSummaryView plain = productApi.createProduct("TERM-B2A-PLAIN", "Unloaded",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(plain.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary@nlolo.co.tz");
        UUID plainVersionId = productApi.getActiveSnapshot(plain.productId(), LocalDate.now()).productVersionId();

        assertThat(productApi.resolveFrequencyLoading(plainVersionId).monthlyPercent())
            .isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * The defect V10 removes: publishVersion called activateWithMeasurementModel unconditionally
     * and the column lived on product_definition, so a republish rewrote the measurement basis of
     * every contract already issued under the product, retroactively and silently.
     */
    @Test
    void republishingWithADifferentMeasurementModelLeavesTheEarlierVersionAlone() {
        ProductSummaryView product = productApi.createProduct("TERM-B1-IFRS", "Two models",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");

        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA,
            LocalDate.now().minusYears(2), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary@nlolo.co.tz");
        UUID firstVersionId = productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId();

        productApi.publishVersion(product.productId(), IfrsMeasurementModel.GMM,
            LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary@nlolo.co.tz");

        assertThat(productApi.getSnapshotByVersionId(firstVersionId).ifrsMeasurementModel())
            .as("a policy pinned to the first version keeps the basis it was issued on")
            .isEqualTo(IfrsMeasurementModel.PAA);
        assertThat(productApi.getActiveSnapshot(product.productId(), LocalDate.now()).ifrsMeasurementModel())
            .isEqualTo(IfrsMeasurementModel.GMM);
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
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null,
            List.of(new ProductApi.BaseRateInput(18, 25, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("15.2000")),
                    new ProductApi.BaseRateInput(18, 25, Sex.MALE, SmokerStatus.NON_SMOKER, new BigDecimal("16.8000"))),
            new EligibilityBounds(18, 25, null, null, null, null),
            ANY_FILING, "actuary@nlolo.co.tz");

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
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
                null,
                List.of(new ProductApi.BaseRateInput(18, 25, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("15.2000"))),
                ANY_FILING, "actuary@nlolo.co.tz"));
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
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary@nlolo.co.tz");

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
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
                null,
                List.of(new ProductApi.BaseRateInput(18, 25, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("15.2000")),
                        new ProductApi.BaseRateInput(18, 25, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("99.9000"))),
                ANY_FILING, "actuary@nlolo.co.tz"));
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
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
                null,
                // The MALE row is valid and is here only so the publish reaches the database:
                // Batch 1's coverage rule refuses a table that cannot price every life the
                // version accepts, and would otherwise reject this before the CHECK is tested.
                List.of(new ProductApi.BaseRateInput(18, 25, Sex.FEMALE, SmokerStatus.NON_SMOKER, BigDecimal.ZERO),
                        new ProductApi.BaseRateInput(18, 25, Sex.MALE, SmokerStatus.NON_SMOKER, new BigDecimal("16.8000"))),
                new EligibilityBounds(18, 25, null, null, null, null),
                ANY_FILING, "actuary@nlolo.co.tz"));
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
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
                null, ANY_FILING, "actuary@nlolo.co.tz"));
        // The message must name the offending band -- a bare constraint violation would leave an
        // actuary to find which of forty rows was the duplicate.
        assertTrue(ex.getMessage().contains("30-39"));
        assertTrue(ex.getMessage().contains("AGE"));
    }

    /**
     * A ZERO MULTIPLIER ZEROES THE PREMIUM, and this is not hypothetical: a real product was
     * published with its AGE band at 0.0000, an applicant was accepted against it, and the
     * premium came out at nil. The insert then hit {@code chk_premium_amount_positive} inside an
     * AFTER_COMMIT listener, so the case sat there reading ACCEPT with no policy behind it and
     * nothing said a word.
     *
     * <p>Nothing anywhere stopped it. The console's schema says {@code z.coerce.number()} with no
     * bound, {@code publishVersion} checked coverage, duplicates and age ranges but never the
     * number itself, and {@code rating_table} had no CHECK. A negative one would have gone
     * through just as far and produced a negative premium.
     *
     * <p>There is no product in which a rating factor of zero is a real design. "Charge this band
     * nothing" is not a rating decision, it is a typo -- an unrated band is expressed by leaving
     * the row out, or by 1.0000.
     */
    @Test
    void aZeroRatingMultiplierIsRefusedAtPublish() {
        ProductSummaryView product = productApi.createProduct("TERM-M13-K", "Zero multiplier",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        InvalidProductVersionException ex = assertThrows(InvalidProductVersionException.class, () ->
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "18-78", BigDecimal.ZERO, 18, 78),
                        new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
                null, ANY_FILING, "actuary@nlolo.co.tz"));
        // Names the band, like every other rating-table refusal here: an actuary should not have
        // to find which of forty rows carried the nil.
        assertTrue(ex.getMessage().contains("18-78"));
        assertTrue(ex.getMessage().contains("AGE"));
    }

    /**
     * A SUM ASSURED RESOLVES TO A BAND BY AMOUNT, NOT BY A STRING NOBODY COULD GUESS.
     *
     * <p>This is V5's AGE defect one factor type over, and it reached production. Underwriting
     * produced one of three band strings hardcoded in Java — LOW, MEDIUM, HIGH, at two and ten
     * million — and asked the product for a row whose band text equalled it. A real product was
     * published with the band {@code "5000000"}, matched none of the three, resolved to the
     * neutral 1.0, and priced every policy as though it had no sum assured factor at all. The row
     * was there, the multiplier was there, the console showed it, and it did nothing.
     */
    @Test
    void aSumAssuredResolvesToTheBandWhoseRangeCoversIt() {
        ProductSummaryView product = productApi.createProduct("TERM-M13-N", "Banded by amount",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "18-78", BigDecimal.ONE, 18, 78),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "Up to 5m",
                        new BigDecimal("1.2000"), null, null,
                        new BigDecimal("0"), new BigDecimal("5000000")),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "Over 5m",
                        new BigDecimal("1.5000"), null, null,
                        new BigDecimal("5000000.01"), new BigDecimal("100000000"))),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary@nlolo.co.tz");
        UUID versionId = productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId();

        assertThat(productApi.resolveSumAssuredMultiplier(versionId, new BigDecimal("3000000")))
            .isEqualByComparingTo(new BigDecimal("1.2000"));
        // INCLUSIVE at both ends, like every other bound on this platform.
        assertThat(productApi.resolveSumAssuredMultiplier(versionId, new BigDecimal("5000000")))
            .isEqualByComparingTo(new BigDecimal("1.2000"));
        assertThat(productApi.resolveSumAssuredMultiplier(versionId, new BigDecimal("30000000")))
            .isEqualByComparingTo(new BigDecimal("1.5000"));
        // Above every band: neutral, not an error. Not every product rates the whole range.
        assertThat(productApi.resolveSumAssuredMultiplier(versionId, new BigDecimal("500000000")))
            .isEqualByComparingTo(BigDecimal.ONE);
    }

    /**
     * A band that RATES must say whom it rates; a neutral one need not.
     *
     * <p>The rule is deliberately asymmetric. A row at exactly 1.0000 changes no price whether it
     * resolves or not, and around eighty fixtures carry one purely to satisfy the coverage rule.
     * A row carrying a real multiplier and no range is the defect itself — a multiplier that can
     * never reach a premium — so it is refused.
     */
    @Test
    void aRatingSumAssuredBandWithNoRangeIsRefusedAtPublish() {
        ProductSummaryView product = productApi.createProduct("TERM-M13-O", "Unbounded band",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        InvalidProductVersionException ex = assertThrows(InvalidProductVersionException.class, () ->
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "18-78", BigDecimal.ONE, 18, 78),
                        new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "5000000",
                            new BigDecimal("1.5000"))),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
                null, ANY_FILING, "actuary@nlolo.co.tz"));
        assertTrue(ex.getMessage().contains("5000000"));
    }

    /** Two bands covering one amount would price on row order -- the defect found five times now. */
    @Test
    void overlappingSumAssuredBandsAreRefusedAtPublish() {
        ProductSummaryView product = productApi.createProduct("TERM-M13-P", "Overlapping bands",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        assertThrows(InvalidProductVersionException.class, () ->
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "18-78", BigDecimal.ONE, 18, 78),
                        new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "A",
                            new BigDecimal("1.2000"), null, null,
                            new BigDecimal("0"), new BigDecimal("5000000")),
                        new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "B",
                            new BigDecimal("1.5000"), null, null,
                            new BigDecimal("4000000"), new BigDecimal("9000000"))),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
                null, ANY_FILING, "actuary@nlolo.co.tz"));
    }

    /** The same refusal below zero, which would price a policy at less than nothing. */
    @Test
    void aNegativeRatingMultiplierIsRefusedAtPublish() {
        ProductSummaryView product = productApi.createProduct("TERM-M13-L", "Negative multiplier",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        assertThrows(InvalidProductVersionException.class, () ->
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "18-78", BigDecimal.ONE, 18, 78),
                        new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", new BigDecimal("-1.0000"))),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
                null, ANY_FILING, "actuary@nlolo.co.tz"));
    }

    /**
     * The database backstop, exercised directly because the application check above makes it
     * unreachable through {@code publishVersion}. A backstop nothing tests is one a future
     * migration drops without anyone noticing -- the same argument as the unique-band constraint.
     */
    @Test
    void theMultiplierConstraintBacksTheZeroCheckAtTheDatabase() {
        ProductSummaryView product = productApi.createProduct("TERM-M13-M", "Multiplier constraint",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary@nlolo.co.tz");

        UUID tenantId = TenantContext.get();
        UUID versionId = productVersionRepository
            .findByTenantIdAndProductIdAndActiveForNewBusinessTrue(tenantId, product.productId())
            .get(0).getProductVersionId();

        assertThrows(DataIntegrityViolationException.class, () ->
            ratingFactorRepository.saveAndFlush(new tz.co.nlolo.lifeplatform.product.domain.RatingFactor(
                tenantId, versionId, FactorType.SUM_ASSURED_BAND.name(), "NIL", BigDecimal.ZERO)));
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
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary@nlolo.co.tz");

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
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
                null, ANY_FILING, "actuary@nlolo.co.tz"));
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
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
                null, ANY_FILING, "actuary@nlolo.co.tz"));
        assertTrue(ex.getMessage().contains("overlap"));
    }

    @Test
    void publishVersionRejectsAnImpossibleAgeRange() {
        ProductSummaryView product = ageProduct("TERM-AGE-C");
        assertThrows(InvalidProductVersionException.class, () ->
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "40-30", BigDecimal.ONE, 40, 30),
                        new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
                null, ANY_FILING, "actuary@nlolo.co.tz"));
    }

    @Test
    void resolveAgeMultiplierPicksTheCoveringBandAndIsNeutralOutsideThem() {
        ProductSummaryView product = ageProduct("TERM-AGE-D");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "18-39", BigDecimal.ONE, 18, 39),
                    new ProductApi.RatingFactorInput(FactorType.AGE, "60-99", new BigDecimal("3.0"), 60, 99),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary@nlolo.co.tz");
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
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary@nlolo.co.tz");
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
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary@nlolo.co.tz");
        UUID tenantId = TenantContext.get();
        UUID versionId = productVersionRepository
            .findByTenantIdAndProductIdAndActiveForNewBusinessTrue(tenantId, product.productId())
            .get(0).getProductVersionId();

        assertThrows(DataIntegrityViolationException.class, () ->
            ratingFactorRepository.saveAndFlush(new tz.co.nlolo.lifeplatform.product.domain.RatingFactor(
                tenantId, versionId, FactorType.OCCUPATION_CLASS.name(), "CLASS_1", BigDecimal.ONE, 18, 39)));
    }

    // ---- M13 step 3: the premium calculation -----------------------------------

    /**
     * A priced version: one band, one occupation class, one sum-assured band.
     *
     * <p>Carries a MALE cell it makes no assertion about, and declares entry ages 18-25. Batch 1
     * refuses a priced version that does not say what ages it sells to, and refuses one whose
     * table cannot price every life inside that declaration -- a version priced for women only
     * could never have been sold to half its market. The quotes below still ask for FEMALE, so
     * the extra row changes no expected number.
     */
    private UUID pricedProduct(String code, BigDecimal ratePerMille) {
        ProductSummaryView product = productApi.createProduct(code, code, ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now().minusDays(1), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE),
                    new ProductApi.RatingFactorInput(FactorType.OCCUPATION_CLASS, "CLASS_1", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null,
            List.of(new ProductApi.BaseRateInput(18, 25, Sex.FEMALE, SmokerStatus.NON_SMOKER, ratePerMille),
                    new ProductApi.BaseRateInput(18, 25, Sex.MALE, SmokerStatus.NON_SMOKER, ratePerMille)),
            new EligibilityBounds(18, 25, null, null, null, null),
            ANY_FILING, "actuary@nlolo.co.tz");
        return product.productId();
    }

    private ProductApi.PremiumQuoteInput quoteFor(UUID productId, LocalDate dateOfBirth, PremiumFrequency frequency) {
        return new ProductApi.PremiumQuoteInput(productId, new BigDecimal("10000000.00"), "TZS",
            dateOfBirth, Sex.FEMALE, SmokerStatus.NON_SMOKER, "CLASS_1", frequency, LocalDate.now());
    }

    /**
     * The quote resolved the sum-assured factor by matching a caller-asserted band STRING -- V9's
     * defect, still live on the illustration path after it was removed from issuance. A product
     * author's band '5000000' could never match what a caller typed, so an illustration and the
     * policy it became were priced by two different mechanisms.
     */
    @Test
    void quotePremiumResolvesTheSumAssuredBandByRangeNotByItsLabel() {
        ProductSummaryView product = productApi.createProduct("TERM-B2A-RANGE", "Range-resolved",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now().minusDays(1), null,
            // A band whose LABEL is nothing a caller would type, carrying a real multiplier.
            List.of(new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "5000000",
                        new BigDecimal("1.5000"), null, null,
                        new BigDecimal("0"), new BigDecimal("20000000")),
                    new ProductApi.RatingFactorInput(FactorType.OCCUPATION_CLASS, "CLASS_1", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null,
            List.of(new ProductApi.BaseRateInput(18, 25, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("10.0000")),
                    new ProductApi.BaseRateInput(18, 25, Sex.MALE, SmokerStatus.NON_SMOKER, new BigDecimal("12.0000"))),
            new EligibilityBounds(18, 25, null, null, null, null), ANY_FILING, "actuary@nlolo.co.tz");

        // 10,000,000 / 1000 * 10.0 = 100,000 annual, x 1.5 sum-assured band = 150,000, / 12.
        ProductApi.PremiumQuoteView quote = productApi.quotePremium(new ProductApi.PremiumQuoteInput(
            product.productId(), new BigDecimal("10000000.00"), "TZS", LocalDate.now().minusYears(20),
            Sex.FEMALE, SmokerStatus.NON_SMOKER, "CLASS_1", PremiumFrequency.MONTHLY, LocalDate.now()));

        assertThat(quote.instalmentAmount()).isEqualByComparingTo(new BigDecimal("12500.00"));
        assertThat(quote.appliedFactors())
            .anySatisfy(f -> {
                assertThat(f.factorType()).isEqualTo(FactorType.SUM_ASSURED_BAND);
                assertThat(f.band()).isEqualTo("5000000");
                assertThat(f.multiplier()).isEqualByComparingTo(new BigDecimal("1.5000"));
            });
    }

    @Test
    void aTermBandedVersionPricesEachTermOnItsOwnRateAndRefusesAnUnpricedTerm() {
        ProductSummaryView product = productApi.createProduct("TERM-V16-BANDED", "Term-banded",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        // Same age/sex/smoker, two term bands at different rates -- the point of V16.
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now().minusDays(1), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE),
                    new ProductApi.RatingFactorInput(FactorType.OCCUPATION_CLASS, "CLASS_1", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null,
            List.of(new ProductApi.BaseRateInput(18, 40, Sex.MALE, SmokerStatus.NON_SMOKER, new BigDecimal("8.0000"), 1, 120),
                    new ProductApi.BaseRateInput(18, 40, Sex.MALE, SmokerStatus.NON_SMOKER, new BigDecimal("14.0000"), 121, 360),
                    // Both sexes are required by rejectUncoveredEntryAges; FEMALE bands mirror MALE's terms.
                    new ProductApi.BaseRateInput(18, 40, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("6.0000"), 1, 120),
                    new ProductApi.BaseRateInput(18, 40, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("11.0000"), 121, 360)),
            new EligibilityBounds(18, 40, null, null, null, null), ANY_FILING, "actuary@nlolo.co.tz");

        // A 10-year (120mo) term prices on the 8.0 band: 1,000,000 / 1000 * 8.0 = 8,000 annual / 12.
        ProductApi.PremiumQuoteView shortTerm = productApi.quotePremium(new ProductApi.PremiumQuoteInput(
            product.productId(), new BigDecimal("1000000.00"), "TZS", LocalDate.now().minusYears(30),
            Sex.MALE, SmokerStatus.NON_SMOKER, "CLASS_1", PremiumFrequency.MONTHLY, LocalDate.now(), 120));
        assertThat(shortTerm.instalmentAmount()).isEqualByComparingTo(new BigDecimal("666.67"));

        // A 20-year (240mo) term prices on the 14.0 band -- a different, higher number.
        ProductApi.PremiumQuoteView longTerm = productApi.quotePremium(new ProductApi.PremiumQuoteInput(
            product.productId(), new BigDecimal("1000000.00"), "TZS", LocalDate.now().minusYears(30),
            Sex.MALE, SmokerStatus.NON_SMOKER, "CLASS_1", PremiumFrequency.MONTHLY, LocalDate.now(), 240));
        assertThat(longTerm.instalmentAmount()).isEqualByComparingTo(new BigDecimal("1166.67"));

        // A term no band covers (30 years = 360mo is the edge; 361 is past it) is refused, not
        // priced at a fallback -- the actuary did not price it.
        assertThrows(PremiumNotQuotableException.class, () -> productApi.quotePremium(new ProductApi.PremiumQuoteInput(
            product.productId(), new BigDecimal("1000000.00"), "TZS", LocalDate.now().minusYears(30),
            Sex.MALE, SmokerStatus.NON_SMOKER, "CLASS_1", PremiumFrequency.MONTHLY, LocalDate.now(), 361)));

        // And a quote with no term at all cannot match a banded row, so it too is refused.
        assertThrows(PremiumNotQuotableException.class, () -> productApi.quotePremium(new ProductApi.PremiumQuoteInput(
            product.productId(), new BigDecimal("1000000.00"), "TZS", LocalDate.now().minusYears(30),
            Sex.MALE, SmokerStatus.NON_SMOKER, "CLASS_1", PremiumFrequency.MONTHLY, LocalDate.now(), null)));
    }

    @Test
    void publishRefusesAnUnbandedRateOverlappingATermBandedOneForTheSameCell() {
        ProductSummaryView product = productApi.createProduct("TERM-V16-OVERLAP", "Overlapping terms",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        InvalidProductVersionException thrown = assertThrows(InvalidProductVersionException.class, () ->
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE),
                        new ProductApi.RatingFactorInput(FactorType.OCCUPATION_CLASS, "CLASS_1", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
                null,
                // An unbanded row (any term) cannot coexist with a banded one for the same cell.
                List.of(new ProductApi.BaseRateInput(18, 40, Sex.MALE, SmokerStatus.NON_SMOKER, new BigDecimal("8.0000")),
                        new ProductApi.BaseRateInput(18, 40, Sex.MALE, SmokerStatus.NON_SMOKER, new BigDecimal("14.0000"), 121, 360)),
                new EligibilityBounds(18, 40, null, null, null, null), ANY_FILING, "actuary@nlolo.co.tz"));
        assertThat(thrown.getMessage()).contains("overlap");
    }

    @Test
    void aSinglePremiumOverTwelveMonthsIsRefused() {
        UUID productId = pricedProduct("TERM-V16-SINGLE", new BigDecimal("10.0000"));
        assertThrows(PremiumNotQuotableException.class, () -> productApi.quotePremium(new ProductApi.PremiumQuoteInput(
            productId, new BigDecimal("1000000.00"), "TZS", LocalDate.now().minusYears(20),
            Sex.FEMALE, SmokerStatus.NON_SMOKER, "CLASS_1", PremiumFrequency.SINGLE, LocalDate.now(), 24)));
    }

    /** An amount no band covers is neutral, matching issuance -- above retention is a soft flag. */
    @Test
    void quotePremiumPricesAnAmountNoBandCoversAtTheNeutralMultiplier() {
        UUID productId = pricedProduct("TERM-B2A-UNCOVERED", new BigDecimal("15.2000"));

        ProductApi.PremiumQuoteView quote = productApi.quotePremium(new ProductApi.PremiumQuoteInput(
            productId, new BigDecimal("10000000.00"), "TZS", LocalDate.now().minusYears(20),
            Sex.FEMALE, SmokerStatus.NON_SMOKER, "CLASS_1", PremiumFrequency.ANNUALLY, LocalDate.now()));

        // pricedProduct's SUM_ASSURED_BAND row carries no amount bounds, so it covers nothing and
        // resolves neutral. 10,000,000 / 1000 * 15.2 = 152,000, unchanged.
        assertThat(quote.instalmentAmount()).isEqualByComparingTo(new BigDecimal("152000.00"));
        assertThat(quote.appliedFactors())
            .noneMatch(f -> f.factorType() == FactorType.SUM_ASSURED_BAND);
    }

    /**
     * Expected values computed BY HAND, not by re-running the implementation's own
     * arithmetic: TZS 10,000,000 / 1,000 = 10,000 units, x 15.2 = 152,000 annual,
     * / 12 = 12,666.666... -> 12,666.67, rounded HALF_UP once at the end.
     */
    @Test
    void quoteASinglePremiumChargesTheWholeAnnualFigureOnceAndDividesByNothing() {
        // The landmine this proves is disarmed: PremiumFrequency.SINGLE carries
        // instalmentsPerYear() == 0 BY DESIGN -- "dividing by this value throws, which is the
        // correct outcome" -- and quotePremium divided by it unconditionally. Quoting a single
        // premium threw ArithmeticException rather than pricing anything.
        UUID productId = pricedProduct("TERM-SINGLE", new BigDecimal("15.2000"));

        ProductApi.PremiumQuoteView quote = productApi.quotePremium(
            quoteFor(productId, LocalDate.now().minusYears(20), PremiumFrequency.SINGLE));

        // The whole price, once. 10,000,000 / 1000 * 15.2 = 152,000, and no division follows.
        assertThat(quote.instalmentAmount()).isEqualByComparingTo(new BigDecimal("152000.00"));
        assertThat(quote.annualAfterFrequencyLoading()).isEqualByComparingTo(new BigDecimal("152000.00"));
        // 0, not 1. A 1 would read as "annually" to any arithmetic that divides by it, which is
        // precisely how a single-premium contract gets put back onto a billing cycle.
        assertEquals(0, quote.instalmentsPerYear());
    }

    @Test
    void aSinglePremiumCarriesNoFrequencyLoading() {
        // A loading prices the cost of spreading payment across the year. A single premium
        // spreads nothing -- the whole amount is held from day one, which is better for the
        // insurer than annually, not worse -- so loading it would be charging for a service
        // the customer did not take.
        UUID productId = pricedProduct("TERM-SINGLE-NOLOAD", new BigDecimal("15.2000"));

        ProductApi.PremiumQuoteView single = productApi.quotePremium(
            quoteFor(productId, LocalDate.now().minusYears(20), PremiumFrequency.SINGLE));
        ProductApi.PremiumQuoteView annually = productApi.quotePremium(
            quoteFor(productId, LocalDate.now().minusYears(20), PremiumFrequency.ANNUALLY));

        assertThat(single.frequencyLoadingPercent()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(single.instalmentAmount()).isEqualByComparingTo(annually.instalmentAmount());
    }

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
        //
        // ONE factor, not two. OCCUPATION_CLASS applies; the SUM_ASSURED_BAND row on
        // pricedProduct carries no amount bounds, so now that the quote resolves that factor by
        // RANGE rather than by matching the band label, it covers no amount and contributes
        // nothing. It never should have: V9 established that a row without bounds rates nobody,
        // and the label match that used to make it appear here is the defect being removed.
        assertThat(quote.appliedFactors())
            .singleElement()
            .satisfies(f -> assertEquals(FactorType.OCCUPATION_CLASS, f.factorType()));
    }

    @Test
    void quotePremiumAppliesTheVersionsFrequencyLoadingAndShowsIt() {
        ProductSummaryView product = productApi.createProduct("TERM-B2A-QLOAD", "Loaded quote",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now().minusDays(1), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE),
                    new ProductApi.RatingFactorInput(FactorType.OCCUPATION_CLASS, "CLASS_1", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null,
            List.of(new ProductApi.BaseRateInput(18, 25, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("1.2000")),
                    new ProductApi.BaseRateInput(18, 25, Sex.MALE, SmokerStatus.NON_SMOKER, new BigDecimal("1.4000"))),
            new EligibilityBounds(18, 25, null, null, null, null),
            new FrequencyLoading(new BigDecimal("8"), new BigDecimal("3")), ANY_FILING, "actuary@nlolo.co.tz");

        // 10,000,000 / 1000 * 1.2 = 12,000 annual. Monthly: x 1.08 = 12,960, / 12 = 1,080.00.
        ProductApi.PremiumQuoteView monthly =
            productApi.quotePremium(quoteFor(product.productId(), LocalDate.now().minusYears(20), PremiumFrequency.MONTHLY));
        assertThat(monthly.annualAfterFactors()).isEqualByComparingTo(new BigDecimal("12000.00"));
        assertThat(monthly.frequencyLoadingPercent()).isEqualByComparingTo(new BigDecimal("8"));
        assertThat(monthly.annualAfterFrequencyLoading()).isEqualByComparingTo(new BigDecimal("12960.00"));
        assertThat(monthly.instalmentAmount()).isEqualByComparingTo(new BigDecimal("1080.00"));

        // Annual is never loaded, and pays less over the year than the monthly payer -- which is
        // the entire point of the change.
        ProductApi.PremiumQuoteView annually =
            productApi.quotePremium(quoteFor(product.productId(), LocalDate.now().minusYears(20), PremiumFrequency.ANNUALLY));
        assertThat(annually.frequencyLoadingPercent()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(annually.instalmentAmount()).isEqualByComparingTo(new BigDecimal("12000.00"));
    }

    @Test
    void anUnloadedVersionQuotesExactlyWhatItDidBefore() {
        UUID productId = pricedProduct("TERM-B2A-NOLOAD", new BigDecimal("15.2000"));
        ProductApi.PremiumQuoteView quote =
            productApi.quotePremium(quoteFor(productId, LocalDate.now().minusYears(20), PremiumFrequency.MONTHLY));

        // 10,000,000 / 1000 * 15.2 = 152,000 / 12 = 12,666.67 -- the figure this product has
        // always quoted. The migration defaults to zero precisely so this cannot move.
        assertThat(quote.frequencyLoadingPercent()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(quote.annualAfterFrequencyLoading()).isEqualByComparingTo(new BigDecimal("152000.00"));
        assertThat(quote.instalmentAmount()).isEqualByComparingTo(new BigDecimal("12666.67"));
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
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null,
            List.of(new ProductApi.BaseRateInput(18, 25, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("20.0000")),
                    // Priced but never quoted here -- the quote below asks for FEMALE. Present so
                    // the version can price every life it accepts, which Batch 1 requires.
                    new ProductApi.BaseRateInput(18, 25, Sex.MALE, SmokerStatus.NON_SMOKER, new BigDecimal("22.0000"))),
            new EligibilityBounds(18, 25, null, null, null, null),
            ANY_FILING, "actuary@nlolo.co.tz");

        ProductApi.PremiumQuoteView quote = productApi.quotePremium(new ProductApi.PremiumQuoteInput(
            product.productId(), new BigDecimal("10000000.00"), "TZS", LocalDate.now().minusYears(20),
            Sex.FEMALE, SmokerStatus.NON_SMOKER, "CLASS_3", PremiumFrequency.MONTHLY, LocalDate.now()));

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
        // sex with no cell.
        //
        // The MALE row is DELETED after publishing rather than never published, because Batch 1's
        // coverage rule means a version priced for one sex only can no longer be published at
        // all. Versions carrying exactly that hole predate the rule and are grandfathered, so the
        // dimension must still refuse rather than fall back to something plausible -- and this is
        // now the only way the state occurs.
        UUID versionId = productVersionRepository
            .findByTenantIdAndProductIdAndActiveForNewBusinessTrue(TenantContext.get(), productId)
            .get(0).getProductVersionId();
        baseRateRepository.deleteAll(baseRateRepository.findByProductVersionId(versionId).stream()
            .filter(r -> "MALE".equals(r.getSex()))
            .toList());
        assertThrows(PremiumNotQuotableException.class, () -> productApi.quotePremium(new ProductApi.PremiumQuoteInput(
            productId, new BigDecimal("10000000.00"), "TZS", LocalDate.now().minusYears(20),
            Sex.MALE, SmokerStatus.NON_SMOKER, "CLASS_1", PremiumFrequency.MONTHLY, LocalDate.now())));
        // smoker status with no cell
        assertThrows(PremiumNotQuotableException.class, () -> productApi.quotePremium(new ProductApi.PremiumQuoteInput(
            productId, new BigDecimal("10000000.00"), "TZS", LocalDate.now().minusYears(20),
            Sex.FEMALE, SmokerStatus.SMOKER, "CLASS_1", PremiumFrequency.MONTHLY, LocalDate.now())));
        // occupation class with no multiplier
        assertThrows(PremiumNotQuotableException.class, () -> productApi.quotePremium(new ProductApi.PremiumQuoteInput(
            productId, new BigDecimal("10000000.00"), "TZS", LocalDate.now().minusYears(20),
            Sex.FEMALE, SmokerStatus.NON_SMOKER, "CLASS_9", PremiumFrequency.MONTHLY, LocalDate.now())));
        // The "sum-assured band with no multiplier" case is gone: the band is no longer an input.
        // An amount no band covers now resolves neutral, which is what issuance does and what
        // quotePremiumPricesAnAmountNoBandCoversAtTheNeutralMultiplier asserts.
    }

    /** An unpriced version is a real state, and says so instead of guessing. */
    @Test
    void quotePremiumRefusesAVersionWithNoBaseRates() {
        ProductSummaryView product = productApi.createProduct("TERM-Q5", "Unpriced", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now().minusDays(1), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "18-25", BigDecimal.ONE, 18, 25),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary@nlolo.co.tz");

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
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
                null,
                List.of(new ProductApi.BaseRateInput(18, 25, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("15.2")),
                        new ProductApi.BaseRateInput(20, 30, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("17.4"))),
                ANY_FILING, "actuary@nlolo.co.tz"));
        assertTrue(ex.getMessage().contains("overlap"));
    }

    /** Adjacent bands are fine -- 18-25 and 26-30 do not overlap. */
    @Test
    void publishVersionAcceptsAdjacentAgeBands() {
        ProductSummaryView product = productApi.createProduct("TERM-Q8", "Adjacent", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null,
            List.of(new ProductApi.BaseRateInput(18, 25, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("15.2")),
                    new ProductApi.BaseRateInput(26, 30, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("17.4")),
                    // The MALE half of the same two bands. This test's subject is adjacency, but
                    // Batch 1 refuses a priced version that cannot price every life it accepts,
                    // and a table covering women only has never been a sellable product.
                    new ProductApi.BaseRateInput(18, 25, Sex.MALE, SmokerStatus.NON_SMOKER, new BigDecimal("16.8")),
                    new ProductApi.BaseRateInput(26, 30, Sex.MALE, SmokerStatus.NON_SMOKER, new BigDecimal("19.1"))),
            new EligibilityBounds(18, 30, null, null, null, null),
            ANY_FILING, "actuary@nlolo.co.tz");

        UUID versionId = productVersionRepository
            .findByTenantIdAndProductIdAndActiveForNewBusinessTrue(TenantContext.get(), product.productId())
            .get(0).getProductVersionId();
        assertEquals(4, baseRateRepository.findByProductVersionId(versionId).size());
    }

    /** Same band, different sex: not an overlap. */
    @Test
    void publishVersionAllowsTheSameBandForADifferentCell() {
        ProductSummaryView product = productApi.createProduct("TERM-Q9", "Both sexes", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null,
            List.of(new ProductApi.BaseRateInput(18, 25, Sex.FEMALE, SmokerStatus.NON_SMOKER, new BigDecimal("15.2")),
                    new ProductApi.BaseRateInput(18, 25, Sex.MALE, SmokerStatus.NON_SMOKER, new BigDecimal("16.8"))),
            new EligibilityBounds(18, 25, null, null, null, null),
            ANY_FILING, "actuary@nlolo.co.tz");

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
        assertEquals(2, rating.baseRates().size());
        assertEquals(2, rating.ratingFactors().size());
        assertEquals(1, rating.benefitSchedule().size());
        // Found by its key rather than by index: findByProductVersionId carries no ORDER BY, so
        // baseRates().get(0) asserted on whichever row Postgres happened to return first.
        assertThat(rating.baseRates())
            .filteredOn(r -> r.sex() == Sex.FEMALE && r.smokerStatus() == SmokerStatus.NON_SMOKER)
            .singleElement()
            .satisfies(r -> assertEquals(0, new BigDecimal("15.2000").compareTo(r.ratePerMille())));
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
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
                null, ANY_FILING, "actuary@nlolo.co.tz"));
    }

    @Test
    void getActiveSnapshotReturnsPublishedVersion() {
        ProductSummaryView product = productApi.createProduct("TERM-06", "Snapshot test", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.GMM, LocalDate.now().minusDays(1), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary@nlolo.co.tz");

        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        assertEquals(IfrsMeasurementModel.GMM, snapshot.ifrsMeasurementModel());
    }

    @Test
    void resolveRatingMultiplierReturnsNeutralWhenBandNotFound() {
        ProductSummaryView product = productApi.createProduct("TERM-07", "Multiplier test", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.GMM, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", new BigDecimal("1.5"), 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary@nlolo.co.tz");
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
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary@nlolo.co.tz");

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
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary@nlolo.co.tz");
        ProductSnapshotView firstSnapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        UUID firstVersionId = firstSnapshot.productVersionId();

        // Second publish on the same product must succeed, not crash.
        assertDoesNotThrow(() -> productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", new BigDecimal("1.25"), 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary@nlolo.co.tz"));

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
    void eligibilityBoundsRoundTripOntoTheVersion() {
        ProductSummaryView product = productApi.createProduct("BOUNDS-01", "Bounded product",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");

        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, List.of(),
            new EligibilityBounds(18, 65, 60, 360, new BigDecimal("500000.00"), new BigDecimal("300000000.00")),
            ANY_FILING, "actuary@nlolo.co.tz");

        ProductVersion version = productVersionRepository
            .findByTenantIdAndProductIdOrderByEffectiveDateDesc(TenantContext.get(), product.productId())
            .getFirst();
        EligibilityBounds bounds = version.getEligibilityBounds();

        assertEquals(18, bounds.minEntryAge());
        assertEquals(65, bounds.maxEntryAge());
        assertEquals(60, bounds.minTermMonths());
        assertEquals(360, bounds.maxTermMonths());
        assertEquals(0, new BigDecimal("500000.00").compareTo(bounds.minSumAssured()));
        assertEquals(0, new BigDecimal("300000000.00").compareTo(bounds.maxSumAssured()));
    }

    /** An unbounded version is a real product design, not a gap. */
    @Test
    void aVersionPublishedWithoutBoundsHasNone() {
        ProductSummaryView product = productApi.createProduct("BOUNDS-02", "Unbounded product",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary@nlolo.co.tz");

        EligibilityBounds bounds = productVersionRepository
            .findByTenantIdAndProductIdOrderByEffectiveDateDesc(TenantContext.get(), product.productId())
            .getFirst().getEligibilityBounds();

        assertNull(bounds.minEntryAge());
        assertNull(bounds.maxEntryAge());
        assertNull(bounds.maxSumAssured());
    }

    @Test
    void invertedBoundsAreRejectedBeforeTheyCanBeStored() {
        assertThrows(IllegalArgumentException.class,
            () -> new EligibilityBounds(65, 18, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> new EligibilityBounds(null, null, 360, 60, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> new EligibilityBounds(null, null, null, null,
                new BigDecimal("100"), new BigDecimal("10")));
    }

    /**
     * The convenience overload must run inside a transaction.
     *
     * <p>This is the regression guard for a bug found while building Build 3: the
     * overload was a {@code default} method on {@code ProductApi}, so Spring's proxy
     * passed it to the target and its delegating call never re-entered the proxy —
     * running the whole publish outside the {@code @Transactional} the implementation
     * declares. ~48 of 67 callers use this overload.
     *
     * <p>Publishing retires every currently-ACTIVE version with {@code saveAndFlush}
     * before inserting the new one, so without a transaction a failure after that point
     * leaves a product with NO active version — unsellable, uncorrected, unlogged.
     *
     * <p><b>This is a structural check, and deliberately so.</b> The behavioural version
     * is not available: forcing the partial write needs a concurrent publisher losing on
     * {@code ux_product_version_active}, and the obvious alternative — wrapping the call
     * in an outer transaction and rolling it back — proves nothing, because Spring
     * Data's own repository transactions would join that outer transaction and roll back
     * whether or not {@code publishVersion} is annotated. So this asserts the two
     * properties that make the failure impossible, which is exactly what a regression
     * would break: no overload is a {@code default} method, and every implementation
     * carries {@code @Transactional}.
     */
    @Test
    void noPublishVersionOverloadIsADefaultMethodAndEveryImplementationIsTransactional() {
        List<Method> declared = Arrays.stream(ProductApi.class.getMethods())
            .filter(m -> m.getName().equals("publishVersion"))
            .toList();
        // Six: step 1 added the cash-value overload, step 2 the payout-plan one. A new overload
        // must raise this count AND pass both checks below -- that is the point of counting.
        assertEquals(6, declared.size(), "expected six publishVersion overloads");
        declared.forEach(m -> assertFalse(m.isDefault(),
            "publishVersion must not be a default method: Spring's proxy cannot apply "
                + "@Transactional to one, so its delegation runs untransacted"));

        List<Method> implementations = Arrays.stream(ProductApiImpl.class.getDeclaredMethods())
            .filter(m -> m.getName().equals("publishVersion"))
            .toList();
        assertEquals(6, implementations.size(), "every overload must be implemented here");
        implementations.forEach(m -> assertNotNull(m.getAnnotation(Transactional.class),
            "every publishVersion implementation must carry @Transactional, including the "
                + "convenience overloads -- the retire-then-insert sequence must be atomic"));
    }

    @Test
    void createProductRejectsDuplicateProductCodeForSameTenant() {
        productApi.createProduct("DUP-01", "First product", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        assertThrows(DuplicateProductCodeException.class, () ->
            productApi.createProduct("DUP-01", "Second product with same code", ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz"));
    }

    // ---- Step 2: what a version pays while the life assured is alive ----

    @Test
    void anAuthoredMoneyBackEndowmentRoundTripsItsPayoutPlan() {
        ProductSummaryView product = productApi.createProduct("END-PAY-1", "Money back twenty",
            ProductCategory.ENDOWMENT, "TZS", "actuary@nlolo.co.tz");
        PayoutPlan plan = PayoutPlan.authored(new PayoutTerms(15, 12, true, new BigDecimal("105")), List.of(
            new PayoutRowInput(PayoutKind.SURVIVAL, 5, 5, PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("10"), PayoutFrequency.ANNUAL),
            new PayoutRowInput(PayoutKind.MATURITY, null, null, PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("100"), null)));

        publishWithPayoutPlan(product.productId(), plan);

        UUID versionId = productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId();
        PayoutPlan read = productApi.resolvePayoutPlan(versionId);
        assertThat(read.authored()).isTrue();
        assertThat(read.terms().freeLookDays()).isEqualTo(15);
        assertThat(read.terms().proofOfLifeIntervalMonths()).isEqualTo(12);
        assertThat(read.terms().survivalBenefitsDeductedFromDeath()).isTrue();
        assertThat(read.terms().deathBenefitPremiumPercent()).isEqualByComparingTo("105");
        // Authoring ORDER survives, because a policy's instalments key back to a row by its index.
        assertThat(read.rows()).extracting(PayoutRowInput::kind)
            .containsExactly(PayoutKind.SURVIVAL, PayoutKind.MATURITY);
        assertThat(read.rows().get(0).fromPolicyYear()).isEqualTo(5);
        assertThat(read.rows().get(0).frequency()).isEqualTo(PayoutFrequency.ANNUAL);
        // The question CoverExpiryDrain asks: this policy matures, it does not merely expire.
        assertThat(read.hasEndOfTermRow()).isTrue();
    }

    @Test
    void aVersionPublishedWithoutAPlanResolvesToNone() {
        ProductSummaryView product = productApi.createProduct("TERM-NOPLAN", "Plain term",
            ProductCategory.TERM_LIFE, "TZS", "actuary@nlolo.co.tz");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            payoutRatingTable(), payoutDeathOnly(), null, ANY_FILING, "actuary@nlolo.co.tz");

        UUID versionId = productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId();
        PayoutPlan read = productApi.resolvePayoutPlan(versionId);
        assertThat(read).isEqualTo(PayoutPlan.none());
        // And the drain's question answers "expire", which is what term insurance does.
        assertThat(read.hasEndOfTermRow()).isFalse();
    }

    private void publishWithPayoutPlan(UUID productId, PayoutPlan plan) {
        productApi.publishVersion(productId, IfrsMeasurementModel.PAA, LocalDate.now(), null,
            payoutRatingTable(), payoutDeathOnly(), null, List.of(), EligibilityBounds.none(),
            FrequencyLoading.none(), ANY_FILING, CashValuePlan.none(), plan, "actuary@nlolo.co.tz");
    }

    private static List<ProductApi.RatingFactorInput> payoutRatingTable() {
        return List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                       new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE));
    }

    private static List<ProductApi.BenefitInput> payoutDeathOnly() {
        return List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED));
    }
}
