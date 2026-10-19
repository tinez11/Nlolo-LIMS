package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.accumulation.AccumulationTestFixtures;
import tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures;
import tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures;
import tz.co.nlolo.lifeplatform.funeral.FuneralTestMigrations;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.Sex;
import tz.co.nlolo.lifeplatform.product.api.BenefitCalculationMethod;
import tz.co.nlolo.lifeplatform.product.api.BenefitType;
import tz.co.nlolo.lifeplatform.product.api.EligibilityBounds;
import tz.co.nlolo.lifeplatform.product.api.FactorType;
import tz.co.nlolo.lifeplatform.product.api.FuneralLifeInput;
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;
import tz.co.nlolo.lifeplatform.product.api.IfrsMeasurementModel;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.ProductCategory;
import tz.co.nlolo.lifeplatform.product.api.ProductSummaryView;
import tz.co.nlolo.lifeplatform.product.api.SmokerStatus;
import tz.co.nlolo.lifeplatform.underwriting.api.QuotationDocumentInput;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingValidationException;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;

/**
 * The quotation an applicant takes away, read back out of the PDF (2026-10-16).
 *
 * <p>Each test reads the TEXT of the document, not just that bytes came back: a PDF that renders and says the wrong
 * premium is worse than none, because an applicant signs it. The figures asserted are the ones the owning module
 * prices -- the rate table, the funeral grid, the deposit band -- since the document is quoted again on the server
 * from inputs, never from figures a browser sent.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import({FuneralTestFixtures.class, AnnuityTestFixtures.class, AccumulationTestFixtures.class})
class QuotationDocumentIntegrationTest {

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
        // Funeral's list already carries the annuity and the deposit migrations, rate tables included.
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            FuneralTestMigrations.ALL);
    }

    private static final UUID TENANT = UUID.randomUUID();

    @Autowired private QuotationDocuments documents;
    @Autowired private FuneralTestFixtures funeral;
    @Autowired private AnnuityTestFixtures annuity;
    @Autowired private AccumulationTestFixtures accumulation;
    @Autowired private ProductApi productApi;
    @Autowired private PartyApi partyApi;

    private String text(QuotationDocumentInput input) {
        byte[] pdf = asTenant(TENANT, () -> documents.render(input));
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String nameOf(UUID party) {
        return asTenant(TENANT, () -> partyApi.getPartyDetail(party).displayName());
    }

    /** A term product priced at 12 per mille for a man, smoking status recorded or not. */
    private UUID[] termProduct() {
        return asTenant(TENANT, () -> {
            ProductSummaryView product = productApi.createProduct("QDOC-TERM-" + UUID.randomUUID().toString().substring(0, 6),
                "Quotation Term", ProductCategory.TERM_LIFE, "TZS", "actuary");
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
                null,
                List.of(new ProductApi.BaseRateInput(18, 78, tz.co.nlolo.lifeplatform.product.api.Sex.MALE,
                            SmokerStatus.NON_SMOKER, new BigDecimal("12.0000")),
                        // The fixture person records no smoking status, so it is priced on the UNKNOWN row.
                        new ProductApi.BaseRateInput(18, 78, tz.co.nlolo.lifeplatform.product.api.Sex.MALE,
                            SmokerStatus.UNKNOWN, new BigDecimal("12.0000")),
                        new ProductApi.BaseRateInput(18, 78, tz.co.nlolo.lifeplatform.product.api.Sex.FEMALE,
                            SmokerStatus.NON_SMOKER, new BigDecimal("9.0000")),
                        new ProductApi.BaseRateInput(18, 78, tz.co.nlolo.lifeplatform.product.api.Sex.FEMALE,
                            SmokerStatus.UNKNOWN, new BigDecimal("9.0000"))),
                new EligibilityBounds(18, 78, null, null, null, null), ANY_FILING, "actuary");
            UUID versionId = productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId();
            return new UUID[] {product.productId(), versionId};
        });
    }

    /**
     * The rate table's own figures, read for the applicant on their record: 12 per mille on a million is 12,000 a
     * year, 1,000 a month, and 120,000 over ten years.
     */
    @Test
    void printsTheRateTablesPremiumForTheApplicantOnTheirRecord() {
        UUID[] term = termProduct();
        UUID applicant = funeral.person(TENANT, 40, Sex.MALE);

        String text = text(new QuotationDocumentInput(term[0], term[1], applicant, new BigDecimal("1000000.00"), "TZS",
            "MONTHLY", 120, null, null, null, List.of()));

        assertThat(text).contains("INDIVIDUAL LIFE PREMIUM QUOTATION");
        assertThat(text).contains(nameOf(applicant));
        assertThat(text).contains("Quotation Term");
        assertThat(text).contains("TZS 1,000,000.00");
        assertThat(text).contains("TZS 1,000.00");
        assertThat(text).contains("TZS 12,000.00");
        assertThat(text).contains("Total payable over the term (120 payments)");
        assertThat(text).contains("TZS 120,000.00");
        assertThat(text).contains("Signature of applicant");
    }

    /**
     * A line per life from the plan's grid. Plan B for a man of 40 and his family: 60,000 + 60,000 + 3 x 6,000 =
     * 138,000 a year, loaded 5% for monthly and divided once -- 12,075.00 a month (FuneralCaseIntegrationTest's figures).
     */
    @Test
    void printsAFuneralFamilyALinePerLife() {
        var product = funeral.publishFamilia(TENANT);
        UUID juma = funeral.person(TENANT, 40, Sex.MALE);
        LocalDate born = asTenant(TENANT, () -> partyApi.getPartyDetail(juma).dateOfBirth());

        List<FuneralLifeInput> lives = new ArrayList<>();
        lives.add(new FuneralLifeInput(FuneralRole.MAIN_MEMBER, nameOf(juma), born, false));
        FuneralTestFixtures.family().forEach(l ->
            lives.add(new FuneralLifeInput(l.role(), l.fullName(), l.dateOfBirth(), l.student())));

        String text = text(new QuotationDocumentInput(product.productId(), product.versionId(), juma, null, "TZS",
            "MONTHLY", null, null, null, "B", lives));

        assertThat(text).contains("FAMILY FUNERAL PREMIUM QUOTATION");
        assertThat(text).contains("The lives covered");
        assertThat(text).contains("Asha").contains("Neema").contains("Baraka").contains("Zawadi");
        assertThat(text).contains("TZS 138,000.00");
        assertThat(text).contains("Each monthly payment");
        assertThat(text).contains("TZS 12,075.00");
    }

    /** A deposit's rate is for the whole term: 5% over twelve months on five million is 250,000, once. */
    @Test
    void printsADepositsValueAtMaturity() {
        var deposit = accumulation.issueDeposit(TENANT, new BigDecimal("5000000.00"), 12, LocalDate.now());
        UUID saver = funeral.person(TENANT, 35, Sex.FEMALE);

        String text = text(new QuotationDocumentInput(deposit.productId(), deposit.productVersionId(), saver,
            new BigDecimal("5000000.00"), "TZS", "SINGLE", 12, null, null, null, List.of()));

        assertThat(text).contains("FIXED-TERM DEPOSIT QUOTATION");
        assertThat(text).contains("5% for the term");
        assertThat(text).contains("TZS 250,000.00");
        assertThat(text).contains("Value at maturity");
        assertThat(text).contains("TZS 5,250,000.00");
    }

    /**
     * An annuity is priced on terms this form does not collect -- the annuity form, the purchase price's rate -- so it
     * is refused by name rather than printed blank. A blank quotation an applicant could sign is the failure.
     */
    @Test
    void refusesAProductWhosePriceThisFormDoesNotCollect() {
        var product = annuity.publish(TENANT, AnnuityTestFixtures.everyForm());
        UUID annuitant = funeral.person(TENANT, 65, Sex.MALE);

        assertThatThrownBy(() -> text(new QuotationDocumentInput(product.productId(), product.versionId(), annuitant,
            new BigDecimal("10000000.00"), "TZS", "SINGLE", null, null, null, null, List.of())))
            .isInstanceOf(UnderwritingValidationException.class)
            .hasMessageContaining("not available");
    }
}
