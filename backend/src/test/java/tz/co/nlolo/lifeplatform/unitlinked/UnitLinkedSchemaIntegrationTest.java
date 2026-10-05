package tz.co.nlolo.lifeplatform.unitlinked;

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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rules unitlinked V3 holds in the database itself, so no code path can get round them: a money-only movement has
 * no fund, one switch waits per policy, a withdrawal is approved by someone other than whoever asked for it, and a
 * top-up's collection purpose is its own. (V3's backfill of U1's splits into the history is checked on the dev
 * database, the only one holding U1 policies when V3 runs.)
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import({UnitLinkedTestFixtures.class, tz.co.nlolo.lifeplatform.funeral.FuneralTestFixtures.class})
class UnitLinkedSchemaIntegrationTest {

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
            UnitLinkedTestMigrations.ALL);
    }

    @Autowired private UnitLinkedTestFixtures fixtures;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void aMoneyOnlyMovementCarriesNoFund() {
        UUID tenant = UUID.randomUUID();
        UUID fund = fixtures.fund(tenant, "EQ1").fundId();
        String insert = "INSERT INTO unitlinked.unit_entry (entry_id, tenant_id, policy_number, fund_id, entry_type, units,"
            + " amount, valuation_date, source_type, source_ref, created_by, created_at)"
            + " VALUES (gen_random_uuid(), ?, 'POL-SCHEMA1', ?, ?, 0, -5000, current_date, 'switch', ?, 'test', now())";
        assertThatThrownBy(() -> jdbc.update(insert, tenant, fund, "SWITCH_FEE", UUID.randomUUID().toString()))
            .hasMessageContaining("unit_entry_money_only_check");
        assertThatThrownBy(() -> jdbc.update(insert, tenant, fund, "SURRENDER_CHARGE", UUID.randomUUID().toString()))
            .hasMessageContaining("unit_entry_money_only_check");
        assertThatCode(() -> jdbc.update(insert, tenant, null, "SWITCH_FEE", UUID.randomUUID().toString()))
            .doesNotThrowAnyException();
    }

    @Test
    void onlyOneSwitchWaitsOnAPolicy() {
        UUID tenant = UUID.randomUUID();
        String insert = "INSERT INTO unitlinked.switch_request (switch_id, tenant_id, policy_number, requested_at,"
            + " requested_by, bound_date) VALUES (gen_random_uuid(), ?, 'POL-SCHEMA2', now(), 'staff-one', current_date)";
        jdbc.update(insert, tenant);
        assertThatThrownBy(() -> jdbc.update(insert, tenant)).hasMessageContaining("ux_switch_waiting");
    }

    @Test
    void aWithdrawalIsNeverApprovedByWhoeverAskedForIt() {
        UUID tenant = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO unitlinked.withdrawal_request (withdrawal_id, tenant_id,"
            + " policy_number, gross_amount, payee_ref, requested_by, requested_at, approved_by, approved_at, status)"
            + " VALUES (gen_random_uuid(), ?, 'POL-SCHEMA3', 150000, '+255700000600', 'staff-one', now(), 'staff-one', now(),"
            + " 'APPROVED')", tenant))
            .hasMessageContaining("withdrawal_request_check");
    }

    @Test
    void aTopUpIsAPurposeOfItsOwn() {
        // payment V14: the collection purpose CHECK names UL_TOP_UP (read from the catalog, not assumed).
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint"
            + " WHERE conname = 'payment_transaction_purpose_check' AND conrelid = 'payment.payment_transaction'::regclass",
            String.class)).contains("UL_TOP_UP");
        // ...and a switch is never a pending order (plan R3): the purpose does not exist there.
        assertThatThrownBy(() -> jdbc.update("INSERT INTO unitlinked.pending_order (order_id, tenant_id, policy_number,"
            + " fund_id, side, amount, sell_all, purpose, received_at, bound_date, source_type, source_ref)"
            + " VALUES (gen_random_uuid(), ?, 'POL-SCHEMA4', gen_random_uuid(), 'SELL', 100, false, 'SWITCH', now(),"
            + " current_date, 'switch', 'x')", UUID.randomUUID()))
            .hasMessageContaining("pending_order_purpose_check");
    }
}
