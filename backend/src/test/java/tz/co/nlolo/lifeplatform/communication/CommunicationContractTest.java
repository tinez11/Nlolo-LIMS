package tz.co.nlolo.lifeplatform.communication;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.communication.api.NotificationApi;
import tz.co.nlolo.lifeplatform.communication.api.NotificationTemplateView;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static tz.co.nlolo.lifeplatform.communication.NextSmsStubs.NEXTSMS_ACCEPTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-level contract coverage for {@code NotificationController} against
 * {@code api/openapi/openapi-communication.yaml}. Validation is STRICT, so an undeclared response
 * field or status is a hard failure -- the same gate every other module's contract test applies.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = { "communication.reminder-drain-interval-ms=3600000", "communication.sms-gateway.live=true" })
class CommunicationContractTest {

    private static final String SPEC_PATH = "api/openapi/openapi-communication.yaml";
    private static final UUID SEEDED_TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static WireMockServer smsGateway;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("communication.sms-gateway-url", () -> smsGateway.baseUrl());
    }

    @BeforeAll
    static void startEverything() throws Exception {
        smsGateway = new WireMockServer(options().dynamicPort());
        smsGateway.start();
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/party/V1__create_party_schema.sql",
            "db-migrations/party/V2__individual_person_record.sql",
            "db-migrations/communication/V1__create_communication_schema.sql",
            "db-migrations/communication/V2__template_identity.sql",
            "db-migrations/communication/V3__seed_offer_templates.sql",
            "db-migrations/communication/V4__dispatch_reason_and_policy.sql",
            "db-migrations/communication/V5__dispatch_claimed_status.sql",
            "db-migrations/audit/V1__create_audit_schema.sql");
    }

    @AfterAll
    static void stopGateway() {
        smsGateway.stop();
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private NotificationApi notificationApi;
    @Autowired private PartyApi partyApi;

    @BeforeEach
    void acceptEverySms() {
        smsGateway.resetAll();
        smsGateway.stubFor(post(urlPathEqualTo("/api/sms/v1/text/single"))
            .willReturn(okJson(NEXTSMS_ACCEPTED)));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static RequestPostProcessor staff() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
            .jwt(builder -> builder.claim("tenant_id", SEEDED_TENANT.toString()));
    }

    private static RequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"),
                new SimpleGrantedAuthority("ROLE_ADMIN"))
            .jwt(builder -> builder.claim("tenant_id", SEEDED_TENANT.toString()));
    }

    private NotificationTemplateView templateFor(String key, String channel, String language) {
        TenantContext.set(SEEDED_TENANT);
        return notificationApi.listTemplates().stream()
            .filter(t -> t.templateKey().equals(key) && t.channel().equals(channel)
                && t.language().equals(language))
            .findFirst().orElseThrow();
    }

    @Test
    void listTemplatesMatchesTheOpenApiContract() throws Exception {
        mockMvc.perform(get("/notifications/templates").with(staff()))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.length()").value(16));
    }

    @Test
    void aTemplateReportsThePlaceholdersItDeclares() throws Exception {
        // The editor's guardrail: somebody rewriting wording has to be able to see which tokens
        // are real, or they will delete one and the message will quietly stop saying its deadline.
        UUID templateId = templateFor("OFFER_MADE", "SMS", "sw").templateId();

        mockMvc.perform(get("/notifications/templates").with(staff()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.templateId=='" + templateId + "')].placeholders[*]")
                .value(org.hamcrest.Matchers.containsInAnyOrder("policyNumber", "premium", "expiryDate")));
    }

    /** The point of the screen: a typo in a customer's SMS is fixable without a migration. */
    @Test
    void editingATemplateBodyChangesWhatIsSentNext() throws Exception {
        UUID templateId = templateFor("COVER_STARTED", "SMS", "sw").templateId();

        mockMvc.perform(put("/notifications/templates/" + templateId).with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bodyTemplate\":\"Hongera! Bima {{policyNumber}} imeanza.\"}"))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));

        // Asserted through a real send rather than by reading the row back. A PUT that updated
        // the database but left the sender reading a cached body would pass a row assertion and
        // still ship the old wording to every customer.
        TenantContext.set(SEEDED_TENANT);
        PartyView party = partyApi.registerIndividual("Reword Target", LocalDate.of(1990, 1, 1),
            "+255713000201", null, "test-staff");
        notificationApi.notify(UUID.randomUUID(), party.partyId(), "POL-REWORD01", "COVER_STARTED",
            Map.of("policyNumber", "POL-REWORD01"));

        String sent = smsGateway.getAllServeEvents().get(0).getRequest().getBodyAsString();
        assertThat(sent).contains("Hongera!");
    }

    /**
     * The edit that must not be allowed through.
     *
     * <p>{@code {{amount}}} is not a token the sender supplies, so this body would render a
     * literal hole into every future COVER_STARTED message. TemplateRenderer would refuse it at
     * send time -- by which point the message is owed to a customer and the only outcome left is
     * a FAILED dispatch nobody asked for.
     */
    @Test
    void rewordingCannotInventAPlaceholderNothingSupplies() throws Exception {
        UUID templateId = templateFor("OFFER_EXPIRED", "SMS", "sw").templateId();

        mockMvc.perform(put("/notifications/templates/" + templateId).with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bodyTemplate\":\"Ofa {{policyNumber}} imefungwa. Deni {{amount}}.\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("amount")));
    }

    @Test
    void droppingAPlaceholderIsAllowed() throws Exception {
        // A shorter message is a legitimate editorial choice. The asymmetry with the test above
        // is deliberate: an unused value harms nobody, an unfilled hole reaches a customer.
        UUID templateId = templateFor("OFFER_MADE", "EMAIL", "en").templateId();

        mockMvc.perform(put("/notifications/templates/" + templateId).with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bodyTemplate\":\"Your offer {{policyNumber}} is ready.\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void aNonAdminStaffMemberCannotRewordATemplate() throws Exception {
        // Read is open to all staff -- answering "what do we actually say" should not need a
        // privileged role -- but an edit reaches thousands of customers and nobody sees it until
        // they do.
        UUID templateId = templateFor("OFFER_CLOSING", "SMS", "sw").templateId();

        mockMvc.perform(put("/notifications/templates/" + templateId).with(staff())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bodyTemplate\":\"Anything at all.\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void aTemplateFromAnotherTenantIsNotFoundRatherThanForbidden() throws Exception {
        UUID templateId = templateFor("OFFER_MADE", "SMS", "sw").templateId();
        RequestPostProcessor otherTenant = jwt()
            .authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
            .jwt(builder -> builder.claim("tenant_id", UUID.randomUUID().toString()));

        // 404 and not 403: distinguishing them would let a caller probe for template ids outside
        // their own tenant.
        mockMvc.perform(put("/notifications/templates/" + templateId).with(otherTenant)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bodyTemplate\":\"Not yours.\"}"))
            .andExpect(status().isNotFound())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void dispatchesCanBeFilteredToOnePolicy() throws Exception {
        TenantContext.set(SEEDED_TENANT);
        PartyView party = partyApi.registerIndividual("Outbox Target", LocalDate.of(1990, 1, 1),
            "+255713000202", null, "test-staff");
        notificationApi.notify(UUID.randomUUID(), party.partyId(), "POL-OUTBOX01", "OFFER_EXPIRED",
            Map.of("policyNumber", "POL-OUTBOX01"));
        notificationApi.notify(UUID.randomUUID(), party.partyId(), "POL-OUTBOX02", "OFFER_EXPIRED",
            Map.of("policyNumber", "POL-OUTBOX02"));

        mockMvc.perform(get("/notifications/dispatches").param("policyNumber", "POL-OUTBOX01").with(staff()))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].policyNumber").value("POL-OUTBOX01"));
    }

    @Test
    void aFailedDispatchCarriesTheReasonItFailed() throws Exception {
        // The whole justification for the failure_reason column: FAILED alone cannot tell an
        // operator an unreachable aggregator from a customer with no phone number on file.
        TenantContext.set(SEEDED_TENANT);
        PartyView unreachable = partyApi.registerIndividual("No Contact", LocalDate.of(1990, 1, 1),
            null, null, "test-staff");
        notificationApi.notify(UUID.randomUUID(), unreachable.partyId(), "POL-OUTBOX03", "OFFER_EXPIRED",
            Map.of("policyNumber", "POL-OUTBOX03"));

        mockMvc.perform(get("/notifications/dispatches")
                .param("policyNumber", "POL-OUTBOX03").param("status", "FAILED").with(staff()))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].failureReason")
                .value(org.hamcrest.Matchers.containsString("No phone number or email")));
    }

    @Test
    void thereIsNoWayToMakeThePlatformSendAMessage() throws Exception {
        // Not an omission. Every legitimate message has a business event behind it that says why;
        // a send endpoint would be a way to make the platform contact anybody on request.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/notifications/dispatches").with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"partyId\":\"" + UUID.randomUUID() + "\",\"templateKey\":\"OFFER_MADE\"}"))
            .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void everyTemplateIsListedForItsOwnTenantOnly() throws Exception {
        RequestPostProcessor otherTenant = jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
            .jwt(builder -> builder.claim("tenant_id", UUID.randomUUID().toString()));

        mockMvc.perform(get("/notifications/templates").with(otherTenant))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void theSeededTemplatesCoverEveryMessageChannelAndLanguage() {
        TenantContext.set(SEEDED_TENANT);
        List<NotificationTemplateView> templates = notificationApi.listTemplates();
        assertThat(templates).hasSize(16);
        assertThat(templates).extracting(NotificationTemplateView::templateKey).containsOnly(
            "OFFER_MADE", "OFFER_CLOSING", "COVER_STARTED", "OFFER_EXPIRED");
    }
}
