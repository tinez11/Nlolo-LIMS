package tz.co.nlolo.lifeplatform.reporting.application;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.bonus.BonusTestFixtures;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.product.api.BonusPlan;
import tz.co.nlolo.lifeplatform.product.api.CashValuePlan;
import tz.co.nlolo.lifeplatform.reporting.ReportingTestMigrations;
import tz.co.nlolo.lifeplatform.reporting.api.RebuildStatusView;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rebuild's promise: what it derives from the journal is exactly what live projection derived. If the
 * two ever differ, a rebuild "repairs" a tenant into a different answer. Lives in the application package
 * so it can call the synchronous rebuild(...) directly.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(BonusTestFixtures.class)
class RebuildIntegrationTest {

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

    @Autowired private BonusTestFixtures fixtures;
    @Autowired private PartyApi partyApi;
    @Autowired private RebuildService rebuild;
    @Autowired private JdbcTemplate jdbc;

    private static final List<String> SNAPSHOTS = List.of(
        "SELECT policy_number, holder_party_id, product_id, product_category, portfolio_code, group_scheme, agent_id,"
            + " sales_channel, branch_code, sum_assured, premium, premium_frequency, currency, issue_date, activated_on,"
            + " not_taken_up_on FROM reporting.report_policy WHERE tenant_id = ? ORDER BY policy_number",
        "SELECT event_id, policy_number, to_status, cause, effective_on, occurred_at"
            + " FROM reporting.report_policy_status_change WHERE tenant_id = ? ORDER BY event_id",
        "SELECT event_id, policy_number, kind, lives_covered, scheme_total_covered, effective_on"
            + " FROM reporting.report_lives_change WHERE tenant_id = ? ORDER BY event_id",
        "SELECT party_id, party_type, kyc_status, display_name, registered_on, date_of_birth, sex"
            + " FROM reporting.report_client WHERE tenant_id = ? ORDER BY party_id",
        "SELECT event_id FROM reporting.applied_event WHERE tenant_id = ? ORDER BY event_id");

    private List<List<Map<String, Object>>> snapshot(UUID tenant) {
        return SNAPSHOTS.stream().map(sql -> jdbc.queryForList(sql, tenant)).toList();
    }

    @Test
    void aRebuildReproducesExactlyWhatLiveProjectionWrote() {
        UUID tenant = UUID.randomUUID();
        var first = fixtures.issue(tenant, BonusPlan.none(), CashValuePlan.none());
        fixtures.issue(tenant, BonusPlan.none(), CashValuePlan.none());
        fixtures.publish(tenant, "policy.PolicyLapsed",
            Map.of("policyNumber", first.policyNumber(), "lapsedAt", Instant.now().toString()));
        TenantContext.set(tenant);
        try {
            partyApi.registerCorporate("Upendo Cooperative", "REG-" + UUID.randomUUID().toString().substring(0, 8),
                "+255717000123", null, "test-staff");
        } finally {
            TenantContext.clear();
        }
        var live = snapshot(tenant);
        assertThat(live.get(0)).hasSize(2);

        RebuildStatusView result = rebuild.rebuild(tenant, Instant.now());

        assertThat(result.state()).isEqualTo("DONE");
        assertThat(result.eventsReplayed()).isPositive();
        assertThat(snapshot(tenant)).isEqualTo(live);
    }

    @Test
    void aRebuildRestoresATenantWhoseRowsWereLost() {
        UUID tenant = UUID.randomUUID();
        fixtures.issue(tenant, BonusPlan.none(), CashValuePlan.none());
        var live = snapshot(tenant);
        for (String table : List.of("report_policy", "report_policy_status_change", "report_lives_change",
                "report_client", "applied_event")) {
            jdbc.update("DELETE FROM reporting." + table + " WHERE tenant_id = ?", tenant);
        }
        rebuild.rebuild(tenant, Instant.now());
        assertThat(snapshot(tenant)).isEqualTo(live);
    }

    @Test
    void startRunsInTheBackgroundAndReportsWhenDone() throws InterruptedException {
        UUID tenant = UUID.randomUUID();
        fixtures.issue(tenant, BonusPlan.none(), CashValuePlan.none());
        assertThat(rebuild.status(tenant).state()).isEqualTo("IDLE");
        assertThat(rebuild.start(tenant, "admin").state()).isEqualTo("RUNNING");
        RebuildStatusView status = rebuild.status(tenant);
        for (int i = 0; i < 60 && "RUNNING".equals(status.state()); i++) {
            Thread.sleep(500);
            status = rebuild.status(tenant);
        }
        assertThat(status.state()).isEqualTo("DONE");
        assertThat(status.finishedAt()).isNotNull();
    }
}
