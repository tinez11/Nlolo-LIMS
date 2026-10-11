package tz.co.nlolo.lifeplatform.reporting;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.reporting.application.ReportingEventHandlers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(classes = Application.class)
class ProjectionIntegrationTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void migrate() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            ReportingTestMigrations.ALL);
    }

    @Autowired private ReportingEventHandlers handlers;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private MeterRegistry meters;
    private final ObjectMapper mapper = new ObjectMapper();

    private final UUID tenant = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID AGENT = UUID.randomUUID();
    private static final UUID HOLDER = UUID.randomUUID();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private boolean apply(UUID eventId, String type, Instant at, Map<String, Object> payload) {
        TenantContext.set(tenant);
        Boolean applied = new TransactionTemplate(txManager).execute(s -> handlers.apply(tenant, eventId, type, at, payload));
        return Boolean.TRUE.equals(applied);
    }

    private Map<String, Object> journal(Map<String, Object> typed) {
        try {
            return mapper.readValue(mapper.writeValueAsString(typed), new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> issued(String policyNumber) {
        Map<String, Object> p = new HashMap<>();
        p.put("policyNumber", policyNumber);
        p.put("policyholderPartyId", HOLDER);
        p.put("productId", PRODUCT);
        p.put("productVersionId", UUID.randomUUID());
        p.put("sumAssured", Map.of("amount", "2000000.00", "currencyCode", "TZS"));
        p.put("premium", Map.of("amount", "15000.00", "currencyCode", "TZS"));
        p.put("premiumFrequency", "MONTHLY");
        p.put("issueDate", "2026-02-01");
        p.put("agentOfRecordId", AGENT);
        p.put("status", "PROPOSED");
        p.put("salesChannel", "AGENCY");
        p.put("branchCode", "DSM-01");
        p.put("portfolioCode", "FUN");
        return p;
    }

    private static Map<String, Object> activated(String policyNumber) {
        Map<String, Object> p = new HashMap<>(issued(policyNumber));
        p.remove("salesChannel");
        p.remove("branchCode");
        p.put("activatedAt", "2026-02-03");
        p.put("productCategory", "FUNERAL");
        p.put("groupScheme", false);
        p.put("livesCovered", 1);
        return p;
    }

    private Map<String, Object> policyRow(String policyNumber) {
        return jdbc.queryForMap("SELECT holder_party_id, product_id, product_category, portfolio_code, group_scheme, agent_id,"
            + " sales_channel, branch_code, sum_assured, premium, premium_frequency, currency, issue_date, activated_on"
            + " FROM reporting.report_policy WHERE tenant_id = ? AND policy_number = ?", tenant, policyNumber);
    }

    private List<Map<String, Object>> statuses(String policyNumber) {
        return jdbc.queryForList("SELECT to_status, cause, effective_on FROM reporting.report_policy_status_change"
            + " WHERE tenant_id = ? AND policy_number = ? ORDER BY effective_on, occurred_at", tenant, policyNumber);
    }

    @Test
    void issuedThenActivatedProjectsOnePolicyAndOneActiveChange() {
        apply(UUID.randomUUID(), "policy.PolicyIssued", Instant.parse("2026-02-01T09:00:00Z"), issued("FUN-1"));
        apply(UUID.randomUUID(), "policy.PolicyActivated", Instant.parse("2026-02-03T09:00:00Z"), activated("FUN-1"));

        Map<String, Object> row = policyRow("FUN-1");
        assertThat(row.get("product_id")).isEqualTo(PRODUCT);
        assertThat(row.get("agent_id")).isEqualTo(AGENT);
        assertThat(row.get("sales_channel")).isEqualTo("AGENCY");
        assertThat(row.get("branch_code")).isEqualTo("DSM-01");
        assertThat(row.get("product_category")).isEqualTo("FUNERAL");
        assertThat(String.valueOf(row.get("activated_on"))).isEqualTo("2026-02-03");
        assertThat(statuses("FUN-1")).extracting(r -> r.get("to_status")).containsExactly("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT lives_covered FROM reporting.report_lives_change WHERE tenant_id = ?"
            + " AND policy_number = 'FUN-1'", Integer.class, tenant)).isEqualTo(1);
    }

    @Test
    void aJournalShapedPayloadProjectsTheSameRowsAsALiveOne() {
        apply(UUID.randomUUID(), "policy.PolicyIssued", Instant.parse("2026-02-01T09:00:00Z"), issued("FUN-2"));
        apply(UUID.randomUUID(), "policy.PolicyActivated", Instant.parse("2026-02-03T09:00:00Z"), activated("FUN-2"));
        apply(UUID.randomUUID(), "policy.PolicyIssued", Instant.parse("2026-02-01T09:00:00Z"), journal(issued("FUN-3")));
        apply(UUID.randomUUID(), "policy.PolicyActivated", Instant.parse("2026-02-03T09:00:00Z"), journal(activated("FUN-3")));
        assertThat(policyRow("FUN-3")).isEqualTo(policyRow("FUN-2"));
    }

    @Test
    void activationArrivingBeforeIssuanceStillKeepsChannelAndBranch() {
        Instant same = Instant.parse("2026-02-03T09:00:00Z");
        apply(UUID.randomUUID(), "policy.PolicyActivated", same, activated("FUN-4"));
        apply(UUID.randomUUID(), "policy.PolicyIssued", same, issued("FUN-4"));
        assertThat(policyRow("FUN-4").get("sales_channel")).isEqualTo("AGENCY");
        assertThat(statuses("FUN-4")).extracting(r -> r.get("to_status")).containsExactly("ACTIVE");
    }

    @Test
    void aRepeatedEventIdIsAppliedOnce() {
        UUID eventId = UUID.randomUUID();
        apply(UUID.randomUUID(), "policy.PolicyActivated", Instant.parse("2026-02-03T09:00:00Z"), activated("FUN-5"));
        Map<String, Object> lapse = Map.of("policyNumber", "FUN-5", "lapsedAt", "2026-05-01T10:00:00Z");
        assertThat(apply(eventId, "policy.PolicyLapsed", Instant.parse("2026-05-01T10:00:00Z"), lapse)).isTrue();
        assertThat(apply(eventId, "policy.PolicyLapsed", Instant.parse("2026-05-01T10:00:00Z"), lapse)).isFalse();
        assertThat(statuses("FUN-5")).extracting(r -> r.get("to_status")).containsExactly("ACTIVE", "LAPSED");
    }

    @Test
    void aLapseForAnUnseenPolicyIsKeptUnderTheUnknownProductAndCounted() {
        double before = meters.counter("lifeplatform_reporting_unattributed_total", "eventType", "policy.PolicyLapsed").count();
        apply(UUID.randomUUID(), "policy.PolicyLapsed", Instant.parse("2026-05-01T10:00:00Z"),
            Map.of("policyNumber", "GHOST-1", "lapsedAt", "2026-05-01T10:00:00Z"));
        assertThat(policyRow("GHOST-1").get("product_id")).isEqualTo(ReportingEventHandlers.UNKNOWN_PRODUCT);
        assertThat(meters.counter("lifeplatform_reporting_unattributed_total", "eventType", "policy.PolicyLapsed").count())
            .isEqualTo(before + 1);
    }

    @Test
    void policySurrenderedMeansThreeDifferentThings() {
        apply(UUID.randomUUID(), "policy.PolicyActivated", Instant.parse("2026-02-03T09:00:00Z"), activated("END-1"));
        apply(UUID.randomUUID(), "policy.PolicyActivated", Instant.parse("2026-02-03T09:00:00Z"), activated("END-2"));
        Map<String, Object> scheme = new HashMap<>(activated("GRP-9"));
        scheme.put("groupScheme", true);
        apply(UUID.randomUUID(), "policy.PolicyActivated", Instant.parse("2026-02-03T09:00:00Z"), scheme);

        apply(UUID.randomUUID(), "policy.PolicySurrendered", Instant.parse("2026-06-01T10:00:00Z"),
            Map.of("policyNumber", "END-1", "surrenderedAt", "2026-06-01T10:00:00Z", "claimId", UUID.randomUUID()));
        apply(UUID.randomUUID(), "policy.PolicySurrendered", Instant.parse("2026-06-01T10:00:00Z"),
            Map.of("policyNumber", "END-2", "surrenderedAt", "2026-06-01T10:00:00Z"));
        apply(UUID.randomUUID(), "policy.PolicySurrendered", Instant.parse("2026-06-01T10:00:00Z"),
            Map.of("policyNumber", "GRP-9", "surrenderedAt", "2026-06-01T10:00:00Z"));

        assertThat(statuses("END-1").get(1).get("to_status")).isEqualTo("CLAIM_TERMINATED");
        assertThat(statuses("END-2").get(1).get("to_status")).isEqualTo("SURRENDERED");
        assertThat(statuses("GRP-9").get(1).get("to_status")).isEqualTo("ENDED");
    }

    @Test
    void paidUpRecordsTheReducedSumAssured() {
        apply(UUID.randomUUID(), "policy.PolicyActivated", Instant.parse("2026-02-03T09:00:00Z"), activated("END-3"));
        apply(UUID.randomUUID(), "policy.PolicyMadePaidUp", Instant.parse("2026-07-01T10:00:00Z"), Map.of(
            "policyNumber", "END-3", "madePaidUpAt", "2026-07-01T10:00:00Z",
            "paidUpSumAssured", Map.of("amount", "800000.00", "currencyCode", "TZS"),
            "originalSumAssured", Map.of("amount", "2000000.00", "currencyCode", "TZS")));
        assertThat(policyRow("END-3").get("sum_assured")).isEqualTo(new BigDecimal("800000.00"));
        assertThat(statuses("END-3").get(1).get("to_status")).isEqualTo("PAID_UP");
    }

    @Test
    void aCollectionIsMobileMoneyOnlyWhenItHasAPayerNumber() {
        apply(UUID.randomUUID(), "billing.PremiumCollected", Instant.parse("2026-03-31T21:30:00Z"), Map.of(
            "invoiceId", UUID.randomUUID(), "policyNumber", "FUN-1", "policyholderPartyId", HOLDER,
            "amount", Map.of("amount", "15000.00", "currencyCode", "TZS"), "collectedAt", "2026-03-31T21:30:00Z",
            "payerRef", "255712000111"));
        apply(UUID.randomUUID(), "billing.PremiumCollected", Instant.parse("2026-04-02T08:00:00Z"), Map.of(
            "invoiceId", UUID.randomUUID(), "policyNumber", "FUN-1", "policyholderPartyId", HOLDER,
            "amount", Map.of("amount", "15000.00", "currencyCode", "TZS"), "collectedAt", "2026-04-02T08:00:00Z"));
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT collected_on, channel FROM reporting.report_collection"
            + " WHERE tenant_id = ? ORDER BY collected_on", tenant);
        // 21:30 UTC on 31 March is 00:30 on 1 April in Dar es Salaam.
        assertThat(String.valueOf(rows.get(0).get("collected_on"))).isEqualTo("2026-04-01");
        assertThat(rows).extracting(r -> r.get("channel")).containsExactly("MOBILE_MONEY", "FIELD_CASH");
    }

    @Test
    void memberEventsRecordTheLivesAfterEachChange() {
        apply(UUID.randomUUID(), "policy.GroupMemberAdded", Instant.parse("2026-03-01T09:00:00Z"), Map.of(
            "policyNumber", "GRP-1", "joinedOn", "2026-03-01", "livesCovered", 41,
            "schemeTotalCovered", Map.of("amount", "41000000.00", "currencyCode", "TZS")));
        apply(UUID.randomUUID(), "policy.GroupMemberExited", Instant.parse("2026-03-09T09:00:00Z"), Map.of(
            "policyNumber", "GRP-1", "policyMemberId", UUID.randomUUID(), "leftOn", "2026-03-09", "reason", "REPAID",
            "livesCovered", 40, "schemeTotalCovered", Map.of("amount", "40000000.00", "currencyCode", "TZS")));
        assertThat(jdbc.queryForList("SELECT kind, lives_covered FROM reporting.report_lives_change WHERE tenant_id = ?"
            + " AND policy_number = 'GRP-1' ORDER BY effective_on", tenant))
            .extracting(r -> r.get("kind") + ":" + r.get("lives_covered")).containsExactly("JOINED:41", "EXITED:40");
    }

    @Test
    void anUnhandledEventTypeIsNotApplied() {
        assertThat(handlers.handles("claims.ClaimRegistered")).isFalse();
        assertThat(apply(UUID.randomUUID(), "claims.ClaimRegistered", Instant.now(), Map.of())).isFalse();
    }
}
