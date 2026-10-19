package tz.co.nlolo.lifeplatform.omnichannel;

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
import tz.co.nlolo.lifeplatform.product.api.GroupFuneralQuote;
import tz.co.nlolo.lifeplatform.product.api.IfrsMeasurementModel;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.ProductCategory;
import tz.co.nlolo.lifeplatform.product.api.ProductSummaryView;
import tz.co.nlolo.lifeplatform.product.api.SmokerStatus;
import tz.co.nlolo.lifeplatform.omnichannel.api.CustomerRequestRefusedException;
import tz.co.nlolo.lifeplatform.omnichannel.application.QuotationDocuments;
import tz.co.nlolo.lifeplatform.omnichannel.domain.DocumentPdf;

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

    @Autowired private QuotationDocuments quotations;
    @Autowired private FuneralTestFixtures funeral;
    @Autowired private AnnuityTestFixtures annuity;
    @Autowired private AccumulationTestFixtures accumulation;
    @Autowired private ProductApi productApi;
    @Autowired private PartyApi partyApi;

    private String text(QuotationDocuments.Request input) {
        byte[] pdf = asTenant(TENANT, () -> DocumentPdf.render(quotations.quotation(input)));
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

        String text = text(new QuotationDocuments.Request(term[0], term[1], applicant, new BigDecimal("1000000.00"), "TZS",
            "MONTHLY", 120, null, null, null, List.of()));

        // The platform's customer-document look: the issuer over the title, as on the policy schedule.
        assertThat(text).contains("NLOLO LIFE").contains("Premium quotation");
        assertThat(text).contains(nameOf(applicant));
        assertThat(text).contains("Quotation Term");
        // What it pays, in the table: the death benefit against the life insured.
        assertThat(text).contains("Death benefit").contains("1,000,000.00");
        // The totals carry the quotation panel's own labels, so paper and screen point at the same line.
        assertThat(text).contains("TZS 1,000.00 a month");
        assertThat(text).contains("Annual equivalent").contains("TZS 12,000.00");
        assertThat(text).contains("Total over 10 years").contains("TZS 120,000.00 (120 payments)");
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

        String text = text(new QuotationDocuments.Request(product.productId(), product.versionId(), juma, null, "TZS",
            "MONTHLY", null, null, null, "B", lives));

        assertThat(text).contains("Funeral plan quotation");
        assertThat(text).contains("Death of spouse").contains("Death of child");
        assertThat(text).contains("Asha").contains("Neema").contains("Baraka").contains("Zawadi");
        assertThat(text).contains("Family total a year").contains("TZS 138,000.00");
        assertThat(text).contains("TZS 12,075.00 a month");
    }

    /** A deposit's rate is for the whole term: 5% over twelve months on five million is 250,000, once. */
    @Test
    void printsADepositsValueAtMaturity() {
        var deposit = accumulation.issueDeposit(TENANT, new BigDecimal("5000000.00"), 12, LocalDate.now());
        UUID saver = funeral.person(TENANT, 35, Sex.FEMALE);

        String text = text(new QuotationDocuments.Request(deposit.productId(), deposit.productVersionId(), saver,
            new BigDecimal("5000000.00"), "TZS", "SINGLE", 12, null, null, null, List.of()));

        assertThat(text).contains("Fixed-term deposit quotation");
        assertThat(text).contains("5% for the term");
        assertThat(text).contains("Interest for the term").contains("250,000.00");
        assertThat(text).contains("Value at maturity").contains("TZS 5,250,000.00");
    }

    /**
     * An annuity is priced on terms this form does not collect -- the annuity form, the purchase price's rate -- so it
     * is refused by name rather than printed blank. A blank quotation an applicant could sign is the failure.
     */
    @Test
    void refusesAProductWhosePriceThisFormDoesNotCollect() {
        var product = annuity.publish(TENANT, AnnuityTestFixtures.everyForm());
        UUID annuitant = funeral.person(TENANT, 65, Sex.MALE);

        assertThatThrownBy(() -> text(new QuotationDocuments.Request(product.productId(), product.versionId(), annuitant,
            new BigDecimal("10000000.00"), "TZS", "SINGLE", null, null, null, null, List.of())))
            .isInstanceOf(CustomerRequestRefusedException.class)
            .hasMessageContaining("not available");
    }

    // ---- Group funeral schemes (2026-10-19) ----------------------------------------------------------------------

    private static LocalDate aged(int years) {
        return LocalDate.now().minusYears(years).minusDays(30);
    }

    /** M001: main member 40, spouse 38, child 10. M002: main member 30 alone. */
    private static List<GroupFuneralQuote.FamilyInput> twoFamilies() {
        return List.of(
            new GroupFuneralQuote.FamilyInput("M001", List.of(
                new FuneralLifeInput(FuneralRole.MAIN_MEMBER, "Juma Ali", aged(40), false),
                new FuneralLifeInput(FuneralRole.SPOUSE, "Asha Juma", aged(38), false),
                new FuneralLifeInput(FuneralRole.CHILD, "Neema Juma", aged(10), false))),
            new GroupFuneralQuote.FamilyInput("M002", List.of(
                new FuneralLifeInput(FuneralRole.MAIN_MEMBER, "Peter Mushi", aged(30), false))));
    }

    /**
     * A group plan that rules extended family but pays no benefit for it on plan B -- the shape of the user's
     * NuruGroup01 / NLevel2, where the screen offered extended family and only the proposal refused it.
     */
    private FuneralTestFixtures.Product groupPlanWithoutExtended() {
        var f = tz.co.nlolo.lifeplatform.product.FuneralPlans.familia();
        return funeral.publish(TENANT, new tz.co.nlolo.lifeplatform.product.api.FuneralPlan(true,
            f.plans().stream().map(p -> new tz.co.nlolo.lifeplatform.product.api.FuneralPlanOption(p.planCode(), p.name(),
                new BigDecimal(p.planCode().equals("A") ? "3000.00" : "5000.00"))).toList(),
            f.benefits().stream().filter(b -> !(b.planCode().equals("B") && b.role() == FuneralRole.EXTENDED)).toList(),
            List.of(), f.roles(), f.maxPricedAge(), f.waitingPeriodMonths(), f.accidentWaivesWaiting(),
            f.dependantClaimPayee(), f.onMainMemberDeath(), f.freeCoverToPaidDate(),
            tz.co.nlolo.lifeplatform.product.api.FuneralSoldAs.GROUP));
    }

    /**
     * The bill is the plan's group rate per MEMBER, whatever each family holds -- 2 members x 5,000 -- and each
     * family's cover is its lives' benefits added up: 2,000,000 + 2,000,000 + 1,000,000 for M001.
     */
    @Test
    void pricesASchemeAtTheGroupRatePerMember() {
        var product = funeral.publishGroupFamilia(TENANT);

        GroupFuneralQuote quote = asTenant(TENANT, () -> productApi.quoteGroupFuneral(product.versionId(),
            new GroupFuneralQuote.Input("B", LocalDate.now(), twoFamilies())));

        assertThat(quote.problems()).isEmpty();
        assertThat(quote.members()).isEqualTo(2);
        assertThat(quote.rate()).isEqualByComparingTo("5000.00");
        assertThat(quote.bill()).isEqualByComparingTo("10000.00");
        assertThat(quote.families().get(0).mainMember()).isEqualTo("Juma Ali");
        assertThat(quote.families().get(0).cover()).isEqualByComparingTo("5000000.00");
        assertThat(quote.families().get(1).cover()).isEqualByComparingTo("2000000.00");
        assertThat(quote.totalCover()).isEqualByComparingTo("7000000.00");
    }

    /**
     * A role the plan pays nothing for is named on its member while the scheme is being written, by the same rules
     * the proposal applies -- not left for the proposal to refuse the whole scheme.
     */
    @Test
    void namesAFamilyMemberThePlanDoesNotCover() {
        var product = groupPlanWithoutExtended();
        var families = List.of(new GroupFuneralQuote.FamilyInput("M001", List.of(
            new FuneralLifeInput(FuneralRole.MAIN_MEMBER, "Martin Lema", aged(34), false),
            new FuneralLifeInput(FuneralRole.EXTENDED, "Martin Fredii", aged(38), false))));

        GroupFuneralQuote quote = asTenant(TENANT, () -> productApi.quoteGroupFuneral(product.versionId(),
            new GroupFuneralQuote.Input("B", LocalDate.now(), families)));

        assertThat(quote.problems()).singleElement().asString()
            .startsWith("Member M001:").contains("Martin Fredii").contains("does not cover");
        assertThat(quote.families().get(0).cover()).as("no cover to state for a family the plan will not take").isNull();
        assertThat(quote.totalCover()).isNull();
        // The bill is still the scheme's: the rate per member does not depend on what a family holds.
        assertThat(quote.bill()).isEqualByComparingTo("5000.00");
    }

    @Test
    void printsTheSchemeQuotationForTheAssociation() {
        var product = funeral.publishGroupFamilia(TENANT);
        UUID association = funeral.association(TENANT);

        byte[] pdf = asTenant(TENANT, () -> DocumentPdf.render(quotations.schemeQuotation(new QuotationDocuments.SchemeRequest(
            product.productId(), product.versionId(), association, "B", null, twoFamilies()))));
        String text;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            text = new PDFTextStripper().getText(doc);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        assertThat(text).contains("NLOLO LIFE").contains("Group funeral scheme quotation");
        assertThat(text).contains(nameOf(association));
        assertThat(text).contains("TZS 5,000.00 per member a month");
        // The plan as people know it, by name, with the code beside it.
        assertThat(text).contains("Familia B (B)");
        assertThat(text).contains("M001").contains("Juma Ali").contains("5,000,000.00");
        assertThat(text).contains("M002").contains("Peter Mushi").contains("2,000,000.00");
        assertThat(text).contains("Bill").contains("TZS 10,000.00 a month");
        assertThat(text).contains("Total cover").contains("TZS 7,000,000.00");
        assertThat(text).contains("Signature for the association");
    }

    /** A scheme the proposal would refuse is not printed for an association to sign. */
    @Test
    void refusesToPrintASchemeWithAFamilyThePlanWillNotTake() {
        var product = groupPlanWithoutExtended();
        UUID association = funeral.association(TENANT);
        var families = List.of(new GroupFuneralQuote.FamilyInput("M001", List.of(
            new FuneralLifeInput(FuneralRole.MAIN_MEMBER, "Martin Lema", aged(34), false),
            new FuneralLifeInput(FuneralRole.EXTENDED, "Martin Fredii", aged(38), false))));

        assertThatThrownBy(() -> asTenant(TENANT, () -> quotations.schemeQuotation(new QuotationDocuments.SchemeRequest(
            product.productId(), product.versionId(), association, "B", null, families))))
            .isInstanceOf(CustomerRequestRefusedException.class)
            .hasMessageContaining("Member M001").hasMessageContaining("does not cover");
    }
}
