package tz.co.nlolo.lifeplatform.accumulation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The ledger cannot be edited or deleted -- by app_role (grants) or by the owner (trigger) -- and
 * cannot be written in a shape that does not add up (the follows trigger). Plain JDBC, no Spring:
 * this is the database's guarantee, and a Java service is exactly what it must not depend on.
 */
@Testcontainers
class LedgerImmutabilityTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    private static final String APP_PASSWORD = "ledger_immutability_password";
    private static final UUID TENANT = UUID.randomUUID();

    @BeforeAll
    static void migrate() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/accumulation/V1__create_accumulation_schema.sql");
        try (Connection c = owner(); Statement s = c.createStatement()) {
            s.execute("ALTER ROLE app_role LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '" + APP_PASSWORD + "'");
            s.execute("INSERT INTO accumulation.posting (posting_id, tenant_id, policy_number, source_type, source_ref, created_by) "
                + "VALUES ('00000000-0000-0000-0000-000000000001', '" + TENANT + "', 'POL-IMMUT01', 'invoice', 'invoice:1', 'test')");
            s.execute("INSERT INTO accumulation.ledger_entry (entry_id, tenant_id, posting_id, policy_number, seq, entry_type, "
                + "amount, balance_after, effective_date, created_by) VALUES ('00000000-0000-0000-0000-000000000011', '"
                + TENANT + "', '00000000-0000-0000-0000-000000000001', 'POL-IMMUT01', 1, 'CONTRIBUTION', 1000.00, 1000.00, "
                + "current_date, 'test')");
        }
    }

    private static Connection owner() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Connection appRole() throws Exception {
        Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app_role", APP_PASSWORD);
        try (Statement s = c.createStatement()) {
            s.execute("SET app.current_tenant_id = '" + TENANT + "'");
        }
        return c;
    }

    @Test
    void appRoleCannotUpdateOrDeleteAnEntry() {
        assertThatThrownBy(() -> { try (Connection c = appRole(); Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE accumulation.ledger_entry SET amount = 1 WHERE seq = 1"); } })
            .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> { try (Connection c = appRole(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM accumulation.ledger_entry WHERE seq = 1"); } })
            .hasMessageContaining("permission denied");
    }

    @Test
    void evenTheOwnerCannotUpdateOrDeleteAnEntryOrAPosting() {
        assertThatThrownBy(() -> { try (Connection c = owner(); Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE accumulation.ledger_entry SET amount = 1 WHERE seq = 1"); } })
            .hasMessageContaining("append-only");
        assertThatThrownBy(() -> { try (Connection c = owner(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM accumulation.posting"); } })
            .hasMessageContaining("append-only");
    }

    @Test
    void anEntryWhoseBalanceDoesNotFollowIsRefused() {
        assertThatThrownBy(() -> { try (Connection c = owner(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO accumulation.ledger_entry (tenant_id, posting_id, policy_number, seq, entry_type, amount, "
                + "balance_after, effective_date, created_by) VALUES ('" + TENANT + "', '00000000-0000-0000-0000-000000000001', "
                + "'POL-IMMUT01', 2, 'POLICY_FEE', -100.00, 950.00, current_date, 'test')"); } })
            .hasMessageContaining("is not 1000.00 + -100.00");
    }

    @Test
    void aGapInTheSequenceIsRefused() {
        assertThatThrownBy(() -> { try (Connection c = owner(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO accumulation.ledger_entry (tenant_id, posting_id, policy_number, seq, entry_type, amount, "
                + "balance_after, effective_date, created_by) VALUES ('" + TENANT + "', '00000000-0000-0000-0000-000000000001', "
                + "'POL-IMMUT01', 5, 'POLICY_FEE', -100.00, 900.00, current_date, 'test')"); } })
            .hasMessageContaining("has no entry 4 before it");
    }

    @Test
    void aBalanceMayNotGoNegative() {
        assertThatThrownBy(() -> { try (Connection c = owner(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO accumulation.ledger_entry (tenant_id, posting_id, policy_number, seq, entry_type, amount, "
                + "balance_after, effective_date, created_by) VALUES ('" + TENANT + "', '00000000-0000-0000-0000-000000000001', "
                + "'POL-IMMUT01', 2, 'POLICY_FEE', -1500.00, -500.00, current_date, 'test')"); } })
            .hasMessageContaining("balance_after");
    }

    @Test
    void theSameSourceCannotPostTwice() {
        assertThatThrownBy(() -> { try (Connection c = owner(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO accumulation.posting (tenant_id, policy_number, source_type, source_ref, created_by) "
                + "VALUES ('" + TENANT + "', 'POL-IMMUT01', 'invoice', 'invoice:1', 'test')"); } })
            .hasMessageContaining("ux_posting_source");
    }

    @Test
    void aCorrectEntryThatFollowsIsAccepted() {
        assertThatCode(() -> { try (Connection c = owner(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO accumulation.ledger_entry (tenant_id, posting_id, policy_number, seq, entry_type, amount, "
                + "balance_after, effective_date, created_by, reverses_entry_id) VALUES ('" + TENANT
                + "', '00000000-0000-0000-0000-000000000001', 'POL-IMMUT01', 2, 'REVERSAL', -1000.00, 0.00, current_date, 'test', "
                + "'00000000-0000-0000-0000-000000000011')"); } })
            .doesNotThrowAnyException();
    }
}
