package tz.co.nlolo.lifeplatform.underwriting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
import tz.co.nlolo.lifeplatform.party.api.KycStatus;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;
import static tz.co.nlolo.lifeplatform.annuity.AnnuityTestFixtures.asTenant;

/**
 * Returned for amendment, amended, resubmitted (2026-10-20): every transition of the state machine, who may take it,
 * what an agent may see, and the history both sides read -- through the endpoints, as the agent and the underwriter
 * call them.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import({FuneralTestFixtures.class, AnnuityTestFixtures.class, AccumulationTestFixtures.class})
@org.springframework.test.context.event.RecordApplicationEvents
class ProposalAmendmentIntegrationTest {

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
            Stream.concat(Stream.of(FuneralTestMigrations.ALL), Stream.of(
                "db-migrations/underwriting/V22__accepted_quotation.sql",
                "db-migrations/underwriting/V23__return_for_amendment.sql")).toArray(String[]::new));
    }

    private static final UUID TENANT = UUID.randomUUID();

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private ProductApi productApi;
    @Autowired private PartyApi partyApi;
    @Autowired private org.springframework.test.context.event.ApplicationEvents events;

    private RequestPostProcessor agent(String subject) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
            .jwt(b -> b.subject(subject).claim("tenant_id", TENANT.toString()));
    }

    /** An underwriter who may also depart from the engine's advice, so the decision itself is never what is tested. */
    private RequestPostProcessor underwriter(String subject) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_UNDERWRITER"),
                new SimpleGrantedAuthority("ROLE_SENIOR_UNDERWRITER"))
            .jwt(b -> b.subject(subject).claim("tenant_id", TENANT.toString()));
    }

    private JsonNode json(ResultActions r) throws Exception {
        return mapper.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private UUID product() {
        return asTenant(TENANT, () -> {
            ProductSummaryView p = productApi.createProduct("PA-TERM-" + UUID.randomUUID().toString().substring(0, 6),
                "Amended Term", ProductCategory.TERM_LIFE, "TZS", "actuary");
            productApi.publishVersion(p.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
                List.of(new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)), null,
                Arrays.stream(Sex.values()).flatMap(sex -> Arrays.stream(SmokerStatus.values())
                    .map(s -> new ProductApi.BaseRateInput(18, 78, sex, s, new BigDecimal("12.0000")))).toList(),
                new EligibilityBounds(18, 78, null, null, null, null), ANY_FILING, "actuary");
            return p.productId();
        });
    }

    /** A client of {@code agent}'s and a proposal the agent opened for them: 1,000,000 over ten years, monthly. */
    private record Opened(UUID caseId, UUID partyId) {}

    private Opened proposal(String agentSubject) throws Exception {
        UUID productId = product();
        UUID versionId = asTenant(TENANT, () -> productApi.getActiveSnapshot(productId, LocalDate.now()).productVersionId());
        UUID party = asTenant(TENANT, () -> partyApi.registerIndividual("Client of " + agentSubject,
            LocalDate.of(1986, 4, 12), "+2557140" + String.format("%05d", (int) (Math.random() * 99999)), null,
            agentSubject).partyId());
        JsonNode opened = json(mvc.perform(post("/underwriting/cases").with(agent(agentSubject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"applicantPartyId\":\"" + party + "\",\"productId\":\"" + productId + "\",\"productVersionId\":\""
                    + versionId + "\",\"sumAssured\":{\"amount\":\"1000000.00\",\"currencyCode\":\"TZS\"},"
                    + "\"requestedTermMonths\":120,\"premiumFrequency\":\"MONTHLY\"}"))
            .andExpect(status().isCreated()));
        return new Opened(UUID.fromString(opened.path("caseId").asText()), party);
    }

    private static final String TWO_REASONS = """
        {"reasons":[
          {"category":"PERSONAL_DETAILS","problem":"The date of birth does not match the ID","change":"Correct it to the ID's"},
          {"category":"COVER_OR_TERM","problem":"A ten-year term is too short for this loan","change":"Make it fifteen years"}],
         "note":"Two things before I can decide"}""";

    private ResultActions returnIt(UUID caseId, String body) throws Exception {
        return mvc.perform(post("/underwriting/cases/" + caseId + "/return").with(underwriter("uw-1"))
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions decide(UUID caseId, String outcome, String reason) throws Exception {
        mvc.perform(post("/underwriting/cases/" + caseId + "/assessments").with(underwriter("uw-assessor"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"assessmentType\":\"MEDICAL\",\"findings\":\"Clear\",\"riskScore\":10}"))
            .andExpect(status().isCreated());
        return mvc.perform(post("/underwriting/cases/" + caseId + "/decision").with(underwriter("uw-1"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"" + outcome + "\",\"reason\":\"" + reason + "\"}"));
    }

    private ResultActions resubmit(String agentSubject, UUID caseId) throws Exception {
        return mvc.perform(post("/underwriting/cases/" + caseId + "/resubmit").with(agent(agentSubject))
            .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Corrected as asked\"}"));
    }

    @Test
    void theUnderwriterReturnsItWithReasonsAndItCannotBeDecidedUntilResubmitted() throws Exception {
        Opened p = proposal("agent-r");

        JsonNode returned = json(returnIt(p.caseId(), TWO_REASONS).andExpect(status().isOk()));
        assertThat(returned.path("status").asText()).isEqualTo("RETURNED");

        decide(p.caseId(), "ACCEPT", "Fine").andExpect(status().isUnprocessableEntity());
        // Returned twice is refused: it is with the agent.
        returnIt(p.caseId(), TWO_REASONS).andExpect(status().isUnprocessableEntity());

        JsonNode history = json(mvc.perform(get("/underwriting/cases/" + p.caseId() + "/history").with(underwriter("uw-1")))
            .andExpect(status().isOk()));
        assertThat(history).hasSize(1);
        assertThat(history.get(0).path("kind").asText()).isEqualTo("RETURNED");
        assertThat(history.get(0).path("round").asInt()).isEqualTo(1);
        assertThat(history.get(0).path("reasons")).hasSize(2);
        assertThat(history.get(0).path("reasons").get(1).path("change").asText()).isEqualTo("Make it fifteen years");
        assertThat(history.get(0).path("note").asText()).isEqualTo("Two things before I can decide");
    }

    @Test
    void aReturnNeedsAReasonWithBothItsTexts() throws Exception {
        Opened p = proposal("agent-v");

        returnIt(p.caseId(), "{\"reasons\":[]}").andExpect(status().isBadRequest());
        returnIt(p.caseId(), "{\"reasons\":[{\"category\":\"OTHER\",\"problem\":\"Wrong\",\"change\":\"\"}]}")
            .andExpect(status().isBadRequest());
        // Only an underwriter returns a proposal.
        mvc.perform(post("/underwriting/cases/" + p.caseId() + "/return").with(agent("agent-v"))
            .contentType(MediaType.APPLICATION_JSON).content(TWO_REASONS)).andExpect(status().isForbidden());
    }

    @Test
    void theAgentSeesTheReasonsAmendsAndResubmitsAndTheHistoryKeepsEveryStep() throws Exception {
        Opened p = proposal("agent-a");
        returnIt(p.caseId(), TWO_REASONS).andExpect(status().isOk());

        JsonNode needs = json(mvc.perform(get("/underwriting/needs-action").with(agent("agent-a"))).andExpect(status().isOk()));
        JsonNode mine = find(needs, p.caseId());
        assertThat(mine.path("actionable").asBoolean()).isTrue();
        assertThat(mine.path("reasons")).hasSize(2);
        assertThat(mine.path("reasons").get(0).path("category").asText()).isEqualTo("PERSONAL_DETAILS");
        assertThat(mine.path("caseView").path("status").asText()).isEqualTo("RETURNED");

        // The contract, as asked: fifteen years.
        JsonNode amended = json(mvc.perform(put("/underwriting/cases/" + p.caseId() + "/terms").with(agent("agent-a"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sumAssured\":1000000,\"requestedTermMonths\":180,\"premiumFrequency\":\"MONTHLY\"}"))
            .andExpect(status().isOk()));
        assertThat(amended.path("requestedTermMonths").asInt()).isEqualTo(180);

        // The client's own record, as asked: the date of birth on the ID.
        mvc.perform(put("/underwriting/cases/" + p.caseId() + "/applicant").with(agent("agent-a"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Client of agent-a\",\"dateOfBirth\":\"1986-04-21\",\"contactInfo\":{}}"))
            .andExpect(status().isOk());

        JsonNode resubmitted = json(resubmit("agent-a", p.caseId()).andExpect(status().isOk()));
        assertThat(resubmitted.path("status").asText()).isEqualTo("OPEN");

        JsonNode history = json(mvc.perform(get("/underwriting/cases/" + p.caseId() + "/history").with(underwriter("uw-1")))
            .andExpect(status().isOk()));
        assertThat(history).extracting(e -> e.path("kind").asText())
            .containsExactly("RETURNED", "AMENDED", "AMENDED", "RESUBMITTED");
        assertThat(history.get(1).path("changes").get(0).path("field").asText()).isEqualTo("Term (months)");
        assertThat(history.get(1).path("changes").get(0).path("before").asText()).isEqualTo("120");
        assertThat(history.get(1).path("changes").get(0).path("after").asText()).isEqualTo("180");
        assertThat(history.get(2).path("changes").get(0).path("field").asText()).isEqualTo("Date of birth");
        assertThat(history.get(3).path("round").asInt()).isEqualTo(2);
        assertThat(history.get(3).path("note").asText()).isEqualTo("Corrected as asked");

        // Off the agent's list, back in the underwriter's hands -- and the existing decision now goes through.
        JsonNode after = json(mvc.perform(get("/underwriting/needs-action").with(agent("agent-a"))).andExpect(status().isOk()));
        assertThat(find(after, p.caseId())).isNull();
        JsonNode decided = json(decide(p.caseId(), "ACCEPT", "Corrected").andExpect(status().isOk()));
        assertThat(decided.path("decisionOutcome").asText()).isEqualTo("ACCEPT");
    }

    /** A corrected identity on a verified client is checked again (user, 2026-10-20). */
    @Test
    void correctingAVerifiedClientsDateOfBirthSendsTheirKycBackToPending() throws Exception {
        Opened p = proposal("agent-k");
        asTenant(TENANT, () -> {
            partyApi.submitKycEvidence(p.partyId(), KycStatus.VERIFIED, "doc-ref", "staff");
            return null;
        });
        returnIt(p.caseId(), TWO_REASONS).andExpect(status().isOk());

        mvc.perform(put("/underwriting/cases/" + p.caseId() + "/applicant").with(agent("agent-k"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Client of agent-k\",\"dateOfBirth\":\"1986-04-21\",\"contactInfo\":{}}"))
            .andExpect(status().isOk());

        assertThat(asTenant(TENANT, () -> partyApi.getPartyDetail(p.partyId()).kycStatus())).isEqualTo(KycStatus.PENDING);
        JsonNode history = json(mvc.perform(get("/underwriting/cases/" + p.caseId() + "/history").with(agent("agent-k"))));
        assertThat(history.get(1).path("changes")).extracting(c -> c.path("field").asText()).contains("KYC status");
    }

    @Test
    void anotherAgentCanNeitherSeeNorTouchTheCaseOrItsReasons() throws Exception {
        Opened p = proposal("agent-a2");
        returnIt(p.caseId(), TWO_REASONS).andExpect(status().isOk());

        mvc.perform(get("/underwriting/cases/" + p.caseId()).with(agent("agent-b"))).andExpect(status().isNotFound());
        mvc.perform(get("/underwriting/cases/" + p.caseId() + "/history").with(agent("agent-b"))).andExpect(status().isNotFound());
        mvc.perform(get("/underwriting/cases/" + p.caseId() + "/disclosures").with(agent("agent-b"))).andExpect(status().isNotFound());
        resubmit("agent-b", p.caseId()).andExpect(status().isNotFound());
        mvc.perform(put("/underwriting/cases/" + p.caseId() + "/terms").with(agent("agent-b"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"sumAssured\":1}"))
            .andExpect(status().isNotFound());
        JsonNode theirs = json(mvc.perform(get("/underwriting/needs-action").with(agent("agent-b"))).andExpect(status().isOk()));
        assertThat(find(theirs, p.caseId())).isNull();
        // Nor can another agent open a proposal for this agent's client.
        mvc.perform(post("/underwriting/cases").with(agent("agent-b")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"applicantPartyId\":\"" + p.partyId() + "\",\"productId\":\"" + UUID.randomUUID()
                    + "\",\"productVersionId\":\"" + UUID.randomUUID() + "\",\"sumAssured\":{\"amount\":\"1.00\",\"currencyCode\":\"TZS\"}}"))
            .andExpect(status().isNotFound());
        // The owner still can.
        mvc.perform(get("/underwriting/cases/" + p.caseId()).with(agent("agent-a2"))).andExpect(status().isOk());
    }

    @Test
    void aPostponedProposalMayBeResubmittedAndTheHistoryKeepsThePostponement() throws Exception {
        Opened p = proposal("agent-p");
        decide(p.caseId(), "POSTPONED", "Recent surgery: try again in six months").andExpect(status().isOk());

        JsonNode needs = json(mvc.perform(get("/underwriting/needs-action").with(agent("agent-p"))));
        JsonNode mine = find(needs, p.caseId());
        assertThat(mine.path("actionable").asBoolean()).isTrue();
        assertThat(mine.path("note").asText()).isEqualTo("Recent surgery: try again in six months");

        JsonNode reopened = json(resubmit("agent-p", p.caseId()).andExpect(status().isOk()));
        assertThat(reopened.path("status").asText()).isEqualTo("OPEN");
        JsonNode history = json(mvc.perform(get("/underwriting/cases/" + p.caseId() + "/history").with(underwriter("uw-1"))));
        assertThat(history.get(0).path("kind").asText()).isEqualTo("RESUBMITTED");
        assertThat(history.get(0).path("previousDecision").path("outcome").asText()).isEqualTo("POSTPONED");
        assertThat(history.get(0).path("previousDecision").path("reason").asText())
            .isEqualTo("Recent surgery: try again in six months");
    }

    @Test
    void aDeclineIsFinalButItsReasonIsShownToTheAgent() throws Exception {
        Opened p = proposal("agent-d");
        decide(p.caseId(), "DECLINED", "Uninsurable medical history").andExpect(status().isOk());

        JsonNode mine = find(json(mvc.perform(get("/underwriting/needs-action").with(agent("agent-d")))), p.caseId());
        assertThat(mine.path("actionable").asBoolean()).isFalse();
        assertThat(mine.path("note").asText()).isEqualTo("Uninsurable medical history");
        assertThat(mine.path("caseView").path("decisionDeclineReason").asText()).isEqualTo("Uninsurable medical history");

        // What needs doing comes first: a returned proposal of the same agent's is listed above the decline.
        Opened returned = proposal("agent-d");
        returnIt(returned.caseId(), TWO_REASONS).andExpect(status().isOk());
        JsonNode list = json(mvc.perform(get("/underwriting/needs-action").with(agent("agent-d"))));
        assertThat(list.get(0).path("caseView").path("caseId").asText()).isEqualTo(returned.caseId().toString());
        assertThat(list.get(list.size() - 1).path("actionable").asBoolean()).isFalse();

        resubmit("agent-d", p.caseId()).andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/underwriting/cases/" + p.caseId() + "/terms").with(agent("agent-d"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"sumAssured\":500000}"))
            .andExpect(status().isUnprocessableEntity());
    }

    /** A typo is refused in words (422), not by a table CHECK as a 500; a return with an empty reason is a 400. */
    @Test
    void badAmendmentsAndReturnsAreRefusedInWordsNotWithA500() throws Exception {
        Opened p = proposal("agent-e");
        returnIt(p.caseId(), "{\"reasons\":[null]}").andExpect(status().isBadRequest());
        returnIt(p.caseId(), TWO_REASONS).andExpect(status().isOk());

        for (String body : List.of(
                "{\"sumAssured\":1000000,\"requestedTermMonths\":0}",
                "{\"sumAssured\":1000000,\"requestedTermMonths\":120,\"premiumPayingTermMonths\":180}",
                "{\"sumAssured\":1000000,\"premiumFrequency\":\"WEEKLY\"}",
                "{\"sumAssured\":0}")) {
            mvc.perform(put("/underwriting/cases/" + p.caseId() + "/terms").with(agent("agent-e"))
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is4xxClientError());
        }
        JsonNode problem = json(mvc.perform(put("/underwriting/cases/" + p.caseId() + "/terms").with(agent("agent-e"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sumAssured\":1000000,\"requestedTermMonths\":120,\"premiumPayingTermMonths\":180}"))
            .andExpect(status().isUnprocessableEntity()));
        assertThat(problem.path("detail").asText()).contains("cannot be paid for longer than the policy runs");
        // Nothing half-applied: the history still holds only the return.
        JsonNode history = json(mvc.perform(get("/underwriting/cases/" + p.caseId() + "/history").with(agent("agent-e"))));
        assertThat(history).hasSize(1);
    }

    @Test
    void nothingIsAmendedOrResubmittedWhileTheUnderwriterHasIt() throws Exception {
        Opened p = proposal("agent-o");

        mvc.perform(put("/underwriting/cases/" + p.caseId() + "/terms").with(agent("agent-o"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"sumAssured\":500000}"))
            .andExpect(status().isUnprocessableEntity());
        resubmit("agent-o", p.caseId()).andExpect(status().isUnprocessableEntity());
        // And a decided case is not returned.
        decide(p.caseId(), "ACCEPT", "Fine").andExpect(status().isOk());
        returnIt(p.caseId(), TWO_REASONS).andExpect(status().isUnprocessableEntity());
    }

    /**
     * The underwriter's arrivals: a proposal an agent corrected and sent back is at the TOP, marked as round 2 -- not
     * wherever it was first opened -- and "back from agents" holds it alone. Its arrival is announced for the email.
     */
    @Test
    void aResubmittedProposalArrivesAtTheTopMarkedAsItsRound() throws Exception {
        Opened older = proposal("agent-q");
        Opened newer = proposal("agent-q");
        returnIt(older.caseId(), TWO_REASONS).andExpect(status().isOk());
        events.clear();
        resubmit("agent-q", older.caseId()).andExpect(status().isOk());

        JsonNode all = json(mvc.perform(get("/underwriting/arrivals").with(underwriter("uw-1"))).andExpect(status().isOk()));
        assertThat(all.path("items").get(0).path("caseView").path("caseId").asText()).isEqualTo(older.caseId().toString());
        assertThat(all.path("items").get(0).path("round").asInt()).isEqualTo(2);
        assertThat(all.path("items").get(0).path("resubmitted").asBoolean()).isTrue();
        JsonNode newerRow = null;
        for (JsonNode row : all.path("items")) {
            if (row.path("caseView").path("caseId").asText().equals(newer.caseId().toString())) newerRow = row;
        }
        assertThat(newerRow).isNotNull();
        assertThat(newerRow.path("round").asInt()).isEqualTo(1);

        JsonNode back = json(mvc.perform(get("/underwriting/arrivals?resubmittedOnly=true").with(underwriter("uw-1")))
            .andExpect(status().isOk()));
        assertThat(back.path("items")).extracting(r -> r.path("caseView").path("caseId").asText())
            .contains(older.caseId().toString()).doesNotContain(newer.caseId().toString());

        // Staff only.
        mvc.perform(get("/underwriting/arrivals").with(agent("agent-q"))).andExpect(status().isForbidden());

        // Announced for the underwriting team's email, as round 2.
        assertThat(events.stream(tz.co.nlolo.lifeplatform.DomainEventEnvelope.class)
                .filter(e -> e.eventType().equals("underwriting.ProposalSubmitted")))
            .singleElement()
            .satisfies(e -> assertThat(((java.util.Map<?, ?>) e.payload()).get("round")).isEqualTo(2));
    }

    private static JsonNode find(JsonNode list, UUID caseId) {
        for (JsonNode n : list) {
            if (n.path("caseView").path("caseId").asText().equals(caseId.toString())) return n;
        }
        return null;
    }
}
