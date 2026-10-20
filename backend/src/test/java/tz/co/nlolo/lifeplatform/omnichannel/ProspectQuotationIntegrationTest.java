package tz.co.nlolo.lifeplatform.omnichannel;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.accumulation.AccumulationTestFixtures;
import tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures;
import tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures;
import tz.co.nlolo.lifeplatform.funeral.FuneralTestMigrations;
import tz.co.nlolo.lifeplatform.omnichannel.application.QuotationDocuments;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.Sex;
import tz.co.nlolo.lifeplatform.product.api.BenefitCalculationMethod;
import tz.co.nlolo.lifeplatform.product.api.BenefitType;
import tz.co.nlolo.lifeplatform.product.api.CashValuePlan;
import tz.co.nlolo.lifeplatform.product.api.EligibilityBounds;
import tz.co.nlolo.lifeplatform.product.api.FactorType;
import tz.co.nlolo.lifeplatform.product.api.FrequencyLoading;
import tz.co.nlolo.lifeplatform.product.api.IfrsMeasurementModel;
import tz.co.nlolo.lifeplatform.product.api.PayoutAmountBasis;
import tz.co.nlolo.lifeplatform.product.api.PayoutFrequency;
import tz.co.nlolo.lifeplatform.product.api.PayoutKind;
import tz.co.nlolo.lifeplatform.product.api.PayoutPlan;
import tz.co.nlolo.lifeplatform.product.api.PayoutRowInput;
import tz.co.nlolo.lifeplatform.product.api.PayoutTerms;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.ProductCategory;
import tz.co.nlolo.lifeplatform.product.api.ProductSummaryView;
import tz.co.nlolo.lifeplatform.product.api.SmokerStatus;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;

/**
 * A prospect's quotation, before anybody is registered (2026-10-20), through the endpoint an agent calls.
 *
 * <p>Each test reads the PDF's TEXT back: the premium, the total over the term, what is paid on death and what is paid
 * at the end -- the four things a prospect takes the paper home to study. And one test counts the rows of every table
 * in the database around a run of quotations, because "nothing is stored until the customer agrees" is the promise
 * the agent makes, and a promise is only as good as the check behind it.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import({FuneralTestFixtures.class, AnnuityTestFixtures.class, AccumulationTestFixtures.class})
class ProspectQuotationIntegrationTest {

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
            FuneralTestMigrations.ALL);
    }

    private static final UUID TENANT = UUID.randomUUID();
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"));

    @Autowired private MockMvc mvc;
    @Autowired private FuneralTestFixtures funeral;
    @Autowired private AccumulationTestFixtures accumulation;
    @Autowired private ProductApi productApi;
    @Autowired private PartyApi partyApi;
    @Autowired private JdbcTemplate jdbc;

    /** The agent: a person record of their own, whose name and phone the quotation prints as its contact. */
    private RequestPostProcessor agent(UUID agentParty) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
            .jwt(b -> b.subject("agent-" + agentParty).claim("tenant_id", TENANT.toString())
                .claim("party_id", agentParty.toString()));
    }

    private byte[] pdf(UUID agentParty, String json) throws Exception {
        return mvc.perform(post("/quotations/pdf").with(agent(agentParty)).contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    private static String text(byte[] pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String prospect(String name, LocalDate born, String sex) {
        return "{\"fullName\":\"" + name + "\",\"phoneNumber\":\"+255713000111\",\"dateOfBirth\":\"" + born
            + "\",\"sex\":\"" + sex + "\",\"smokerStatus\":\"NON_SMOKER\",\"occupationClass\":\"STANDARD\"}";
    }

    /** A money-back endowment: 10% of the sum assured as a survival benefit in year 5, all of it at maturity. */
    private static final PayoutPlan MONEY_BACK = PayoutPlan.authored(new PayoutTerms(15, null, true, null), List.of(
        new PayoutRowInput(PayoutKind.SURVIVAL, 5, 5, PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("10"), PayoutFrequency.ANNUAL),
        new PayoutRowInput(PayoutKind.MATURITY, null, null, PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("100"), null)));

    /** Priced at 12 per mille for a non-smoking man of any age the table covers. */
    private UUID[] endowment() {
        return asTenant(TENANT, () -> {
            ProductSummaryView product = productApi.createProduct("PQ-END-" + UUID.randomUUID().toString().substring(0, 6),
                "Prospect Endowment", ProductCategory.ENDOWMENT, "TZS", "actuary");
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)), null,
                // Every sex and smoking status at 12 per mille: a version must price every life it accepts.
                java.util.Arrays.stream(tz.co.nlolo.lifeplatform.product.api.Sex.values())
                    .flatMap(sex -> java.util.Arrays.stream(SmokerStatus.values())
                        .map(smoker -> new ProductApi.BaseRateInput(18, 78, sex, smoker, new BigDecimal("12.0000"))))
                    .toList(),
                new EligibilityBounds(18, 78, null, null, null, null), FrequencyLoading.none(), ANY_FILING,
                CashValuePlan.none(), MONEY_BACK, "actuary");
            UUID versionId = productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId();
            return new UUID[] {product.productId(), versionId};
        });
    }

    private UUID agentParty() {
        return funeral.person(TENANT, 35, Sex.FEMALE);
    }

    private String nameOf(UUID party) {
        return asTenant(TENANT, () -> partyApi.getPartyDetail(party).displayName());
    }

    /**
     * 12 per mille on a million is 12,000 a year, 1,000 a month, 240,000 over twenty years. Survival year 5 pays
     * 100,000 on the fifth anniversary; maturity pays the million on the twentieth.
     */
    @Test
    void anEndowmentProspectSeesWhatTheyPayAndWhatTheyGetBack() throws Exception {
        UUID[] product = endowment();
        UUID agent = agentParty();

        String text = text(pdf(agent, "{\"productId\":\"" + product[0] + "\",\"productVersionId\":\"" + product[1]
            + "\",\"prospect\":" + prospect("Halima Prospect", TODAY.minusYears(40).minusDays(30), "MALE")
            + ",\"sumAssured\":1000000,\"currency\":\"TZS\",\"frequency\":\"MONTHLY\",\"policyTermMonths\":240}"));

        // Marked as a prospect's, with its validity and who to call.
        assertThat(text).contains("Premium quotation").contains("Prospect").contains("Halima Prospect");
        assertThat(text).contains("This is a quotation for a prospect, valid until "
            + DMY.format(QuotationDocuments.validUntil(TODAY)));
        assertThat(text).contains("Prepared by").contains(nameOf(agent));
        assertThat(text).contains("Signature of prospect");
        // What they pay.
        assertThat(text).contains("TZS 1,000.00 a month");
        assertThat(text).contains("Annual equivalent").contains("TZS 12,000.00");
        assertThat(text).contains("Total over 20 years").contains("TZS 240,000.00 (240 payments)");
        // What is paid on death, and how it is decided for this version.
        assertThat(text).contains("Death benefit").contains("1,000,000.00");
        assertThat(text).contains("On death the policy pays the sum assured shown above.");
        assertThat(text).contains("Survival benefits already paid are deducted from the death benefit.");
        // What is paid while it runs and at its end, dated from the schedule.
        assertThat(text).contains("Survival benefit on " + DMY.format(TODAY.plusYears(5))).contains("100,000.00");
        assertThat(text).contains("Maturity benefit on " + DMY.format(TODAY.plusMonths(240)));
        // Conditions.
        assertThat(text).contains("within 15 days of the policy being issued");
        assertThat(text).contains("The final premium is confirmed when the proposal is accepted");
        assertThat(text).contains("The policy terms and conditions govern what is paid.");
    }

    /** 5% for the term on five million: 250,000 of interest, 5,250,000 at maturity. */
    @Test
    void aDepositProspectSeesItsValueAtMaturityAndWhatDeathPays() throws Exception {
        var deposit = accumulation.issueDeposit(TENANT, new BigDecimal("5000000.00"), 12, LocalDate.now());

        String text = text(pdf(agentParty(), "{\"productId\":\"" + deposit.productId() + "\",\"productVersionId\":\""
            + deposit.productVersionId() + "\",\"prospect\":" + prospect("Saida Saver", TODAY.minusYears(35), "FEMALE")
            + ",\"sumAssured\":5000000,\"currency\":\"TZS\",\"frequency\":\"SINGLE\",\"policyTermMonths\":12}"));

        assertThat(text).contains("Fixed-term deposit quotation").contains("Saida Saver");
        assertThat(text).contains("This is a quotation for a prospect, valid until");
        assertThat(text).contains("Deposit").contains("5,000,000.00");
        assertThat(text).contains("Interest for the term").contains("250,000.00");
        assertThat(text).contains("Value at maturity").contains("TZS 5,250,000.00");
        assertThat(text).contains("On death before maturity, the deposit plus the interest earned to that date is paid.");
        assertThat(text).contains("The final figures are confirmed when the proposal is accepted");
    }

    /** Plan B for a man of 40 and his family: 138,000 a year, 12,075.00 a month (QuotationDocumentIntegrationTest's). */
    @Test
    void aFuneralProspectSeesTheBenefitPerLifeAndTheWaitingPeriod() throws Exception {
        var product = funeral.publishFamilia(TENANT);
        LocalDate born = TODAY.minusYears(40).minusDays(30);
        String lives = "[{\"role\":\"MAIN_MEMBER\",\"name\":\"Juma Prospect\",\"dateOfBirth\":\"" + born + "\",\"student\":false},"
            + FuneralTestFixtures.family().stream().map(l -> "{\"role\":\"" + l.role() + "\",\"name\":\"" + l.fullName()
                + "\",\"dateOfBirth\":\"" + l.dateOfBirth() + "\",\"student\":" + l.student() + "}")
                .collect(Collectors.joining(",")) + "]";

        String text = text(pdf(agentParty(), "{\"productId\":\"" + product.productId() + "\",\"productVersionId\":\""
            + product.versionId() + "\",\"prospect\":" + prospect("Juma Prospect", born, "MALE")
            + ",\"currency\":\"TZS\",\"frequency\":\"MONTHLY\",\"funeralPlanCode\":\"B\",\"lives\":" + lives + "}"));

        Integer waiting = asTenant(TENANT, () -> productApi.resolveFuneralPlan(product.versionId()).waitingPeriodMonths());
        assertThat(text).contains("Funeral plan quotation").contains("Juma Prospect");
        assertThat(text).contains("Death of spouse").contains("Asha").contains("Zawadi");
        assertThat(text).contains("Family total a year").contains("TZS 138,000.00");
        assertThat(text).contains("TZS 12,075.00 a month");
        assertThat(text).contains("The plan pays nothing when the policy ends");
        assertThat(text).contains(waiting != null && waiting > 0
            ? "waiting period of " + waiting + " months" : "There is no waiting period");
    }

    /**
     * The promise an agent makes a customer: quoting, changing the inputs and taking the PDF away store NOTHING. Every
     * table in every schema is counted before and after, so a row written anywhere -- a party, a case, an audit row, a
     * quotation -- fails this, not only the tables somebody thought to list.
     */
    @Test
    void quotingAndDownloadingStoreNothingAnywhere() throws Exception {
        UUID[] product = endowment();
        var deposit = accumulation.issueDeposit(TENANT, new BigDecimal("2000000.00"), 12, LocalDate.now());
        UUID agent = agentParty();

        Map<String, Long> before = rowCounts();
        for (String amount : List.of("500000", "750000", "1000000")) {
            pdf(agent, "{\"productId\":\"" + product[0] + "\",\"productVersionId\":\"" + product[1] + "\",\"prospect\":"
                + prospect("Nothing Stored", TODAY.minusYears(30), "MALE") + ",\"sumAssured\":" + amount
                + ",\"currency\":\"TZS\",\"frequency\":\"MONTHLY\",\"policyTermMonths\":240}");
        }
        pdf(agent, "{\"productId\":\"" + deposit.productId() + "\",\"productVersionId\":\"" + deposit.productVersionId()
            + "\",\"prospect\":" + prospect("Nothing Stored", TODAY.minusYears(30), "MALE")
            + ",\"sumAssured\":2000000,\"frequency\":\"SINGLE\",\"policyTermMonths\":12}");
        mvc.perform(post("/products/" + product[0] + "/premium-quote").with(agent(agent))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sumAssuredAmount\":1000000.00,\"sumAssuredCurrency\":\"TZS\",\"dateOfBirth\":\""
                    + TODAY.minusYears(30) + "\",\"sex\":\"MALE\",\"smokerStatus\":\"NON_SMOKER\","
                    + "\"occupationClass\":\"STANDARD\",\"frequency\":\"MONTHLY\",\"policyTermMonths\":240}"))
            .andExpect(status().isOk());

        assertThat(rowCounts()).isEqualTo(before);
    }

    /** One name, or the other: a quotation for nobody, or for two people at once, is refused before anything is priced. */
    @Test
    void refusesAQuotationNamingNeitherOrBoth() throws Exception {
        UUID[] product = endowment();
        UUID agent = agentParty();
        String inputs = ",\"sumAssured\":1000000,\"frequency\":\"MONTHLY\",\"policyTermMonths\":240}";

        mvc.perform(post("/quotations/pdf").with(agent(agent)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"" + product[0] + "\",\"productVersionId\":\"" + product[1] + "\"" + inputs))
            .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/quotations/pdf").with(agent(agent)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"" + product[0] + "\",\"productVersionId\":\"" + product[1]
                    + "\",\"applicantPartyId\":\"" + agent + "\",\"prospect\":"
                    + prospect("Two People", TODAY.minusYears(30), "MALE") + inputs))
            .andExpect(status().isUnprocessableEntity());
    }

    private Map<String, Long> rowCounts() {
        List<String> tables = jdbc.queryForList("""
            SELECT quote_ident(table_schema) || '.' || quote_ident(table_name) FROM information_schema.tables
             WHERE table_type = 'BASE TABLE' AND table_schema NOT IN ('pg_catalog', 'information_schema')
             ORDER BY 1""", String.class);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : tables) {
            counts.put(table, jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class));
        }
        assertThat(counts).as("the database this test counts must hold tables").hasSizeGreaterThan(20);
        return counts;
    }
}
