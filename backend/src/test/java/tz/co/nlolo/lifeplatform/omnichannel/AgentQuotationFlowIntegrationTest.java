package tz.co.nlolo.lifeplatform.omnichannel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.ResultActions;
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
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.product.api.BenefitCalculationMethod;
import tz.co.nlolo.lifeplatform.product.api.BenefitType;
import tz.co.nlolo.lifeplatform.product.api.EligibilityBounds;
import tz.co.nlolo.lifeplatform.product.api.FactorType;
import tz.co.nlolo.lifeplatform.product.api.IfrsMeasurementModel;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.ProductCategory;
import tz.co.nlolo.lifeplatform.product.api.ProductSummaryView;
import tz.co.nlolo.lifeplatform.product.api.Sex;
import tz.co.nlolo.lifeplatform.product.api.SmokerStatus;
import tz.co.nlolo.lifeplatform.underwriting.api.AcceptedQuotationApi;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseView;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;

/**
 * The customer agrees, and the quotation becomes a proposal (2026-10-20): confirm saves it, cancel stores nothing,
 * onboarding reads it back, and submitting opens the underwriter's case from the SAVED quotation -- for the agent who
 * confirmed it and nobody else.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import({FuneralTestFixtures.class, AnnuityTestFixtures.class, AccumulationTestFixtures.class})
class AgentQuotationFlowIntegrationTest {

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
            Stream.concat(Stream.of(FuneralTestMigrations.ALL),
                Stream.of("db-migrations/underwriting/V22__accepted_quotation.sql")).toArray(String[]::new));
    }

    private static final UUID TENANT = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"));

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ProductApi productApi;
    @Autowired private PartyApi partyApi;
    @Autowired private UnderwritingApi underwritingApi;
    @Autowired private AcceptedQuotationApi acceptedQuotations;
    @Autowired private FuneralTestFixtures funeral;

    private RequestPostProcessor agent(String subject) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
            .jwt(b -> b.subject(subject).claim("tenant_id", TENANT.toString()));
    }

    private RequestPostProcessor staff() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
            .jwt(b -> b.subject("staff-underwriter").claim("tenant_id", TENANT.toString()));
    }

    /** A term product at 12 per mille for everybody, terms of up to 30 years. */
    private UUID[] term() {
        return asTenant(TENANT, () -> {
            ProductSummaryView product = productApi.createProduct("AQ-TERM-" + UUID.randomUUID().toString().substring(0, 6),
                "Agreed Term", ProductCategory.TERM_LIFE, "TZS", "actuary");
            productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)), null,
                Arrays.stream(Sex.values()).flatMap(sex -> Arrays.stream(SmokerStatus.values())
                    .map(s -> new ProductApi.BaseRateInput(18, 78, sex, s, new BigDecimal("12.0000")))).toList(),
                new EligibilityBounds(18, 78, null, null, null, null), ANY_FILING, "actuary");
            return new UUID[] {product.productId(),
                productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId()};
        });
    }

    private static String prospectQuote(UUID[] product, String name) {
        return "{\"productId\":\"" + product[0] + "\",\"productVersionId\":\"" + product[1] + "\",\"prospect\":"
            + "{\"fullName\":\"" + name + "\",\"phoneNumber\":\"+255713000222\",\"dateOfBirth\":\""
            + TODAY.minusYears(40).minusDays(30) + "\",\"sex\":\"MALE\",\"smokerStatus\":\"NON_SMOKER\"}"
            + ",\"sumAssured\":1000000,\"currency\":\"TZS\",\"frequency\":\"MONTHLY\",\"policyTermMonths\":120}";
    }

    private ResultActions confirm(String subject, String body) throws Exception {
        return mvc.perform(post("/agent-quotations").with(agent(subject)).contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    private JsonNode json(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    /** Somebody this agent registered, as onboarding would. */
    private UUID registeredBy(String subject, String name) {
        return asTenant(TENANT, () -> partyApi.registerIndividual(name, TODAY.minusYears(40).minusDays(30),
            "+2557130" + String.format("%05d", Math.abs(name.hashCode()) % 100000), null, subject).partyId());
    }

    private long quotationRows() {
        return jdbc.queryForObject("SELECT count(*) FROM underwriting.accepted_quotation", Long.class);
    }

    @Test
    void confirmingSavesTheInputsAndTheServersOwnFigures() throws Exception {
        UUID[] product = term();
        long before = quotationRows();

        JsonNode saved = json(confirm("agent-a", prospectQuote(product, "Rehema Agreed")).andExpect(status().isCreated()));

        assertThat(quotationRows()).isEqualTo(before + 1);
        assertThat(saved.path("inputs").path("prospect").path("fullName").asText()).isEqualTo("Rehema Agreed");
        assertThat(saved.path("productVersionId").asText()).isEqualTo(product[1].toString());
        // The server's figures, priced again from the inputs: 12 per mille on a million is 1,000.00 a month.
        assertThat(saved.path("quoted").path("quote").path("instalmentAmount").decimalValue())
            .isEqualByComparingTo("1000.00");
        assertThat(saved.path("quoted").path("sumAssured").decimalValue()).isEqualByComparingTo("1000000");
        assertThat(saved.path("quotedOn").asText()).isEqualTo(TODAY.toString());
        assertThat(saved.path("validUntil").asText()).isEqualTo(TODAY.plusDays(30).toString());
        assertThat(saved.path("caseId").isNull()).isTrue();
    }

    /**
     * Quote, download, proceed and cancel: every table in the database is counted before and after, and nothing moved --
     * the agent's cancel at the confirmation step sends nothing, and a cancel after confirming deletes what was saved.
     */
    @Test
    void cancelAtEitherStepLeavesTheDatabaseAsItWas() throws Exception {
        UUID[] product = term();
        Map<String, Long> before = rowCounts();

        mvc.perform(post("/quotations/pdf").with(agent("agent-c")).contentType(MediaType.APPLICATION_JSON)
            .content(prospectQuote(product, "Cancel Twice"))).andExpect(status().isOk());
        // Cancel after confirming: the agent's discard.
        String id = json(confirm("agent-c", prospectQuote(product, "Cancel Twice"))).path("quotationId").asText();
        mvc.perform(delete("/agent-quotations/" + id).with(agent("agent-c"))).andExpect(status().isNoContent());

        assertThat(rowCounts()).isEqualTo(before);
    }

    @Test
    void anotherAgentCanNeitherReadNorDiscardIt() throws Exception {
        String id = json(confirm("agent-a", prospectQuote(term(), "Mine Only"))).path("quotationId").asText();

        mvc.perform(get("/agent-quotations/" + id).with(agent("agent-b"))).andExpect(status().isNotFound());
        mvc.perform(delete("/agent-quotations/" + id).with(agent("agent-b"))).andExpect(status().isNotFound());
        mvc.perform(get("/agent-quotations/" + id).with(agent("agent-a"))).andExpect(status().isOk());
    }

    @Test
    void anAgentCannotQuoteOrProposeForAnotherAgentsClient() throws Exception {
        UUID[] product = term();
        UUID theirs = registeredBy("agent-b", "Not Your Client");
        long before = quotationRows();

        confirm("agent-a", "{\"productId\":\"" + product[0] + "\",\"productVersionId\":\"" + product[1]
            + "\",\"applicantPartyId\":\"" + theirs + "\",\"sumAssured\":1000000,\"frequency\":\"MONTHLY\","
            + "\"policyTermMonths\":120}").andExpect(status().isNotFound());
        assertThat(quotationRows()).isEqualTo(before);

        String id = json(confirm("agent-a", prospectQuote(product, "Wrong Client"))).path("quotationId").asText();
        mvc.perform(post("/agent-quotations/" + id + "/proposal").with(agent("agent-a"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"applicantPartyId\":\"" + theirs + "\"}"))
            .andExpect(status().isNotFound());
    }

    /**
     * The proposal is the saved quotation: product version, sum assured, term and frequency all come from what was
     * saved, the case is linked to it, the underwriter reads it, and it can no longer be discarded.
     */
    @Test
    void submittingOpensTheCaseFromTheSavedQuotationForTheUnderwriter() throws Exception {
        UUID[] product = term();
        String id = json(confirm("agent-a", prospectQuote(product, "Proposed Person"))).path("quotationId").asText();
        UUID client = registeredBy("agent-a", "Proposed Person");

        JsonNode submitted = json(mvc.perform(post("/agent-quotations/" + id + "/proposal").with(agent("agent-a"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"applicantPartyId\":\"" + client + "\"}"))
            .andExpect(status().isCreated()));

        UUID caseId = UUID.fromString(submitted.path("caseId").asText());
        assertThat(submitted.path("proposalNumber").asText()).isNotBlank();
        UnderwritingCaseView opened = asTenant(TENANT, () -> underwritingApi.getCase(caseId));
        assertThat(opened.applicantPartyId()).isEqualTo(client);
        assertThat(opened.productVersionId()).isEqualTo(product[1]);
        assertThat(opened.sumAssuredAmount()).isEqualByComparingTo("1000000");
        assertThat(opened.requestedTermMonths()).isEqualTo(120);
        assertThat(opened.premiumFrequency()).isEqualTo("MONTHLY");
        assertThat(opened.openedBy()).isEqualTo("agent-a");

        JsonNode seen = json(mvc.perform(get("/underwriting/cases/" + caseId + "/quotation").with(staff()))
            .andExpect(status().isOk()));
        assertThat(seen.path("quotationId").asText()).isEqualTo(id);
        assertThat(seen.path("quoted").path("quote").path("instalmentAmount").decimalValue()).isEqualByComparingTo("1000.00");
        // Another agent cannot read what this one's customer was quoted.
        mvc.perform(get("/underwriting/cases/" + caseId + "/quotation").with(agent("agent-b")))
            .andExpect(status().isNotFound());

        mvc.perform(delete("/agent-quotations/" + id).with(agent("agent-a"))).andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/agent-quotations/" + id + "/proposal").with(agent("agent-a"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"applicantPartyId\":\"" + client + "\"}"))
            .andExpect(status().isUnprocessableEntity());
    }

    /** A funeral plan's family travels from the quotation into the case, and the case is opened at the main benefit. */
    @Test
    void aFuneralProposalCarriesTheFamilyItWasQuotedFor() throws Exception {
        var product = funeral.publishFamilia(TENANT);
        LocalDate born = TODAY.minusYears(40).minusDays(30);
        String lives = "[{\"role\":\"MAIN_MEMBER\",\"name\":\"Juma Agreed\",\"dateOfBirth\":\"" + born + "\",\"student\":false},"
            + FuneralTestFixtures.family().stream().map(l -> "{\"role\":\"" + l.role() + "\",\"name\":\"" + l.fullName()
                + "\",\"dateOfBirth\":\"" + l.dateOfBirth() + "\",\"student\":" + l.student() + "}")
                .collect(Collectors.joining(",")) + "]";
        String id = json(confirm("agent-a", "{\"productId\":\"" + product.productId() + "\",\"productVersionId\":\""
            + product.versionId() + "\",\"prospect\":{\"fullName\":\"Juma Agreed\",\"dateOfBirth\":\"" + born
            + "\",\"sex\":\"MALE\"},\"currency\":\"TZS\",\"frequency\":\"MONTHLY\",\"funeralPlanCode\":\"B\",\"lives\":"
            + lives + "}").andExpect(status().isCreated())).path("quotationId").asText();
        UUID client = asTenant(TENANT, () -> partyApi.registerIndividual("Juma Agreed", born, "+255713099001", null,
            "agent-a").partyId());

        JsonNode submitted = json(mvc.perform(post("/agent-quotations/" + id + "/proposal").with(agent("agent-a"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"applicantPartyId\":\"" + client + "\"}"))
            .andExpect(status().isCreated()));

        UUID caseId = UUID.fromString(submitted.path("caseId").asText());
        var application = asTenant(TENANT, () -> underwritingApi.funeralApplication(caseId)).orElseThrow();
        assertThat(application.planCode()).isEqualTo("B");
        assertThat(application.dependants()).extracting(l -> l.fullName()).contains("Asha", "Neema", "Baraka", "Zawadi");
        UnderwritingCaseView opened = asTenant(TENANT, () -> underwritingApi.getCase(caseId));
        assertThat(opened.sumAssuredAmount()).isEqualByComparingTo("2000000");
    }

    /** Only what reached the underwriter remains: the end-of-day sweep removes the rest and keeps the submitted. */
    @Test
    void theSweepDiscardsWhatWasNeverSubmitted() throws Exception {
        UUID[] product = term();
        String abandoned = json(confirm("agent-s", prospectQuote(product, "Walked Away"))).path("quotationId").asText();
        String kept = json(confirm("agent-s", prospectQuote(product, "Went Ahead"))).path("quotationId").asText();
        UUID client = registeredBy("agent-s", "Went Ahead");
        mvc.perform(post("/agent-quotations/" + kept + "/proposal").with(agent("agent-s"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"applicantPartyId\":\"" + client + "\"}"))
            .andExpect(status().isCreated());

        int discarded = acceptedQuotations.discardUnsubmittedBefore(Instant.now().plusSeconds(60));

        assertThat(discarded).isGreaterThanOrEqualTo(1);
        mvc.perform(get("/agent-quotations/" + abandoned).with(agent("agent-s"))).andExpect(status().isNotFound());
        mvc.perform(get("/agent-quotations/" + kept).with(agent("agent-s"))).andExpect(status().isOk());
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
        assertThat(counts).containsKey("underwriting.accepted_quotation");
        return counts;
    }
}
