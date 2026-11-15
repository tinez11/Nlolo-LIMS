package tz.co.nlolo.lifeplatform.audit;

import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;

import org.springframework.jdbc.core.JdbcTemplate;
import tz.co.nlolo.lifeplatform.audit.domain.AuditLogEntry;
import tz.co.nlolo.lifeplatform.audit.infrastructure.AuditLogRepository;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The activity log through the real stack (2026-11-14, the user: who did what, when, to what, seen by admins). A
 * change and a refusal are recorded with the person, the action in words and the outcome; the event the change raised
 * is listed under it; what the platform did by itself is a System line; and only audit.view reads any of it.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ActivityLogIntegrationTest {

    private static final String SPEC = "api/openapi/openapi-audit.yaml";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void migrate() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V8__ifrs17_branches_and_channels.sql",
            "db-migrations/organisation/V1__organisation_settings.sql");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper json;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private AuditLogRepository auditLog;
    @Autowired private JdbcTemplate jdbc;
    private final UUID tenant = UUID.randomUUID();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private RequestPostProcessor as(String role, String subject, String name) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_" + role))
            .jwt(b -> b.subject(subject).claim("tenant_id", tenant.toString()).claim("name", name));
    }

    private JsonNode read(String body) throws Exception {
        return json.readTree(body);
    }

    @Test
    void whoDidWhatWhenToWhatAndWhatFollowed() throws Exception {
        RequestPostProcessor asha = as("ADMIN", "sub-asha", "Asha Admin");
        mockMvc.perform(put("/organisation/profile").with(asha).contentType("application/json").content("""
                {"legalName":"Bima Bora Life Assurance Ltd","tradingName":"Bima Bora","registrationNumber":"TIRA/LI/021",
                 "address":"Plot 12, Samora Avenue","phone":"+255 22 211 0369","email":"care@bimabora.co.tz"}"""))
            .andExpect(status().isOk());
        mockMvc.perform(put("/organisation/branding").with(asha).contentType("application/json").content("""
                {"primaryColour":"navy","secondaryColour":"#0E8C7F","typeface":"SANS","layout":"CLASSIC","paper":"A4",
                 "hasLogo":false}"""))
            .andExpect(status().isBadRequest());
        // Reading is not recorded.
        mockMvc.perform(get("/organisation/settings").with(asha)).andExpect(status().isOk());

        JsonNode page = read(mockMvc.perform(get("/activity").with(asha))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            .andReturn().getResponse().getContentAsString());
        JsonNode lines = page.get("items");
        assertThat(lines).hasSize(2);
        JsonNode refused = lines.get(0);
        JsonNode saved = lines.get(1);

        assertThat(refused.get("actorName").asText()).isEqualTo("Asha Admin");
        assertThat(refused.get("actorKind").asText()).isEqualTo("STAFF");
        assertThat(refused.get("label").asText()).isEqualTo("Saved the branding and document layout");
        assertThat(refused.get("outcome").asText()).isEqualTo("REFUSED");
        assertThat(refused.get("reason").asText()).isEqualTo("Colours are written #RRGGBB, for example #17324D.");
        assertThat(refused.get("kind").asText()).isEqualTo("settings");

        assertThat(saved.get("label").asText()).isEqualTo("Saved the company's profile");
        assertThat(saved.get("outcome").asText()).isEqualTo("DONE");
        assertThat(saved.get("eventCount").asInt()).as("the event the change raised is joined to it").isPositive();

        JsonNode detail = read(mockMvc.perform(get("/activity/" + saved.get("id").asText()).with(asha))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            .andReturn().getResponse().getContentAsString());
        assertThat(detail.get("events")).isNotEmpty();
        assertThat(detail.get("changes").findValuesAsText("field")).contains("legalName", "phone");
        // Masked opened too (2026-11-15, the user): the phone shows by its last three, in the body and in what changed.
        assertThat(detail.get("technical").get("body").asText()).doesNotContain("211 0369").contains("369\"");
        JsonNode phone = null;
        for (JsonNode c : detail.get("changes")) if ("phone".equals(c.get("field").asText())) phone = c;
        assertThat(phone).isNotNull();
        assertThat(phone.get("after").asText()).doesNotContain("211").endsWith("369");
        assertThat(detail.get("line").get("actedAlone").asBoolean()).isFalse();

        // The person filter, and the people who acted.
        assertThat(read(mockMvc.perform(get("/activity").param("person", "sub-asha").param("outcome", "REFUSED")
                .with(asha)).andReturn().getResponse().getContentAsString()).get("items")).hasSize(1);
        assertThat(read(mockMvc.perform(get("/activity/people").with(asha))
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            .andReturn().getResponse().getContentAsString()).findValuesAsText("name")).containsExactly("Asha Admin");

        String csv = mockMvc.perform(get("/activity.csv").with(asha)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(csv.lines()).hasSize(3).first().isEqualTo("When,Who,Did,Record,Amount,Outcome,Reason");
        assertThat(csv).contains("Asha Admin (staff)", "Refused", "Saved the company's profile");
    }

    @Test
    void whatThePlatformDidByItselfIsASystemLine() throws Exception {
        TenantContext.set(tenant);
        new TransactionTemplate(transactions).executeWithoutResult(status -> events.publishEvent(
            DomainEventEnvelope.of("policy.PolicyLapsed", tenant, Map.of("policyNumber", "POL-SYS-1"))));
        TenantContext.clear();

        JsonNode lines = read(mockMvc.perform(get("/activity").param("recordType", "policy").param("recordId", "POL-SYS-1")
                .with(as("ADMIN", "sub-asha", "Asha Admin")))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            .andReturn().getResponse().getContentAsString()).get("items");
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).get("actorKind").asText()).isEqualTo("SYSTEM");
        assertThat(lines.get(0).get("label").asText()).isEqualTo("policy.PolicyLapsed");
        assertThat(lines.get(0).get("recordLabel").asText()).isEqualTo("POL-SYS-1");
        // Named and openable like an action (2026-11-15 critique: System lines had no record to open).
        assertThat(lines.get(0).get("recordType").asText()).isEqualTo("policy");
        assertThat(lines.get(0).get("recordId").asText()).isEqualTo("POL-SYS-1");
    }

    /**
     * 2026-11-15 critique: every event from before the log began read as "System" did it -- 6,083 in dev -- and an
     * approval of one's own work was a separate "Acted alone" line rather than said on the action.
     */
    @Test
    void beforeTheLogNobodyCanSayWho_andActingAloneIsSaidOnTheAction() throws Exception {
        UUID action = UUID.randomUUID();
        Instant now = Instant.now();
        TenantContext.set(tenant);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            auditLog.save(new AuditLogEntry(UUID.randomUUID(), tenant, UUID.randomUUID(), "claims.ClaimAssessed", 1, null,
                Instant.parse("2026-11-05T09:00:00Z"), Instant.parse("2026-11-05T09:00:00Z"),
                "{\"claimId\":\"c-before\",\"fraudIndicator\":false}", null));
            jdbc.update("""
                INSERT INTO audit.activity (activity_id, tenant_id, occurred_at, actor_subject, actor_name, actor_kind,
                    http_method, route, label, kind, record_type, record_id, outcome, status_code)
                VALUES (?, ?, ?, 'sub-asha', 'Asha Admin', 'STAFF', 'POST', '/withdrawals/{id}/approve',
                    'Approved a withdrawal', 'money', 'policy', 'POL-ALONE-1', 'DONE', 200)""",
                action, tenant, java.sql.Timestamp.from(now));
            auditLog.save(new AuditLogEntry(UUID.randomUUID(), tenant, UUID.randomUUID(), "access.ActedAlone", 1, null,
                now, now, "{\"policyNumber\":\"POL-ALONE-1\",\"what\":\"withdrawal\"}", action));
        });
        TenantContext.clear();

        JsonNode lines = read(mockMvc.perform(get("/activity").param("from", "2026-11-01T00:00:00Z")
                .with(as("ADMIN", "sub-asha", "Asha Admin")))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            .andReturn().getResponse().getContentAsString()).get("items");
        assertThat(lines).hasSize(2);
        JsonNode alone = lines.get(0);
        JsonNode before = lines.get(1);

        assertThat(alone.get("label").asText()).isEqualTo("Approved a withdrawal");
        assertThat(alone.get("actedAlone").asBoolean()).as("said on the action").isTrue();
        assertThat(alone.get("eventCount").asInt()).isEqualTo(1);

        assertThat(before.get("actorKind").asText()).as("not 'System': nobody can say who").isEqualTo("UNRECORDED");
        assertThat(before.get("recordType").asText()).isEqualTo("claim");
        assertThat(before.get("recordId").asText()).isEqualTo("c-before");

        JsonNode opened = read(mockMvc.perform(get("/activity/" + before.get("id").asText())
                .with(as("ADMIN", "sub-asha", "Asha Admin")))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            .andReturn().getResponse().getContentAsString());
        assertThat(opened.get("line").get("actorKind").asText()).isEqualTo("UNRECORDED");
        assertThat(opened.get("line").get("recordId").asText()).isEqualTo("c-before");

        String csv = mockMvc.perform(get("/activity.csv").param("from", "2026-11-01T00:00:00Z")
                .with(as("ADMIN", "sub-asha", "Asha Admin")))
            .andReturn().getResponse().getContentAsString();
        assertThat(csv).contains("Not recorded");
    }

    @Test
    void onlyAuditViewReadsIt() throws Exception {
        mockMvc.perform(get("/activity").with(as("FINANCE_OFFICER", "sub-fin", "Finance"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/activity.csv").with(as("FINANCE_OFFICER", "sub-fin", "Finance"))).andExpect(status().isForbidden());
    }
}
