package tz.co.nlolo.lifeplatform.reporting;

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

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The real thing, end to end: issuing and activating a policy through PolicyApi, and registering a client
 * through PartyApi, reach the reporting tables with no test code in between. */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(BonusTestFixtures.class)
class LiveProjectionIntegrationTest {

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

    private static final UUID TENANT = UUID.randomUUID();
    @Autowired private BonusTestFixtures fixtures;
    @Autowired private PartyApi partyApi;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void anIssuedAndActivatedPolicyAndItsHolderReachTheReadModel() {
        var issued = fixtures.issue(TENANT, BonusPlan.none(), CashValuePlan.none());

        assertThat(jdbc.queryForObject("SELECT product_id FROM reporting.report_policy WHERE tenant_id = ? AND policy_number = ?",
            UUID.class, TENANT, issued.policyNumber())).isEqualTo(issued.productId());
        assertThat(jdbc.queryForList("SELECT to_status FROM reporting.report_policy_status_change WHERE tenant_id = ?"
            + " AND policy_number = ?", String.class, TENANT, issued.policyNumber())).containsExactly("ACTIVE");
        UUID holder = jdbc.queryForObject("SELECT holder_party_id FROM reporting.report_policy WHERE tenant_id = ?"
            + " AND policy_number = ?", UUID.class, TENANT, issued.policyNumber());
        assertThat(jdbc.queryForObject("SELECT party_type FROM reporting.report_client WHERE tenant_id = ? AND party_id = ?",
            String.class, TENANT, holder)).isEqualTo("INDIVIDUAL");
        assertThat(jdbc.queryForObject("SELECT date_of_birth FROM reporting.report_client WHERE tenant_id = ? AND party_id = ?",
            LocalDate.class, TENANT, holder)).isEqualTo(LocalDate.of(1985, 1, 1));
    }

    @Test
    void aRegisteredCorporateBecomesAClient() {
        TenantContext.set(TENANT);
        UUID corporate;
        try {
            corporate = partyApi.registerCorporate("Mkombozi Traders Ltd", "REG-" + UUID.randomUUID().toString().substring(0, 8),
                "+255717000999", null, "test-staff").partyId();
        } finally {
            TenantContext.clear();
        }
        assertThat(jdbc.queryForObject("SELECT party_type || ':' || kyc_status FROM reporting.report_client"
            + " WHERE tenant_id = ? AND party_id = ?", String.class, TENANT, corporate)).isEqualTo("CORPORATE:PENDING");
    }
}
