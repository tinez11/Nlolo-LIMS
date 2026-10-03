package tz.co.nlolo.lifeplatform.bonus;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * The bonus record cannot be edited or deleted, cannot be written in a shape that does not add up,
 * and cannot attach one declaration twice -- all the database's guarantee, so no Java is involved.
 */
@Testcontainers
class BonusLedgerImmutabilityTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    private static final String APP_PASSWORD = "bonus_immutability_password";
    private static final UUID TENANT = UUID.randomUUID();
    private static final String DECL = "00000000-0000-0000-0000-0000000000d1";

    @BeforeAll
    static void migrate() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/bonus/V1__create_bonus_schema.sql");
        try (Connection c = owner(); Statement s = c.createStatement()) {
            s.execute("ALTER ROLE app_role LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '" + APP_PASSWORD + "'");
            s.execute("INSERT INTO bonus.declaration (declaration_id, tenant_id, product_id, valuation_date, "
                + "reversionary_rate_percent, terminal_rate_percent, status, proposed_by, approved_by) VALUES ('" + DECL
                + "', '" + TENANT + "', gen_random_uuid(), current_date, 3, 50, 'APPROVED', 'a', 'b')");
            s.execute(entry("POL-B1", 1, "REVERSIONARY", "30000.00", "30000.00", "declaration:" + DECL + ":POL-B1", null));
            // A second policy of its own for the reversal test, so no test changes another's seq 2.
            s.execute(entry("POL-B9", 1, "REVERSIONARY", "30000.00", "30000.00", "declaration:" + DECL + ":POL-B9", null));
            s.execute("INSERT INTO bonus.declaration_outcome (tenant_id, declaration_id, policy_number, outcome) "
                + "VALUES ('" + TENANT + "', '" + DECL + "', 'POL-B1', 'ATTACHED')");
        }
    }

    private static String entry(String policy, int seq, String type, String amount, String totalAfter, String ref,
                                String reversesSeq) {
        boolean reversionary = type.equals("REVERSIONARY");
        return "INSERT INTO bonus.attachment_entry (tenant_id, policy_number, seq, entry_type, amount, total_after, "
            + "effective_date, declaration_id, basis_amount, rate_percent, source_type, source_ref, reverses_entry_id, created_by) "
            + "VALUES ('" + TENANT + "', '" + policy + "', " + seq + ", '" + type + "', " + amount + ", " + totalAfter
            + ", current_date, " + (reversionary ? "'" + DECL + "', 1000000, 3" : "NULL, NULL, NULL") + ", '"
            + (reversionary ? "declaration" : "reversal") + "', '" + ref + "', "
            + (reversesSeq == null ? "NULL" : "(SELECT entry_id FROM bonus.attachment_entry WHERE policy_number = '"
                + policy + "' AND seq = " + reversesSeq + ")")
            + ", 'test')";
    }

    private static Connection owner() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Connection appRole(UUID tenant) throws Exception {
        Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app_role", APP_PASSWORD);
        try (Statement s = c.createStatement()) {
            s.execute("SET app.current_tenant_id = '" + tenant + "'");
        }
        return c;
    }

    private static void ownerRun(String sql) throws Exception {
        try (Connection c = owner(); Statement s = c.createStatement()) { s.execute(sql); }
    }

    @Test
    void appRoleCannotUpdateOrDeleteAnEntryOrAnOutcome() {
        assertThatThrownBy(() -> { try (Connection c = appRole(TENANT); Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE bonus.attachment_entry SET amount = 1"); } }).hasMessageContaining("permission denied");
        assertThatThrownBy(() -> { try (Connection c = appRole(TENANT); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM bonus.declaration_outcome"); } }).hasMessageContaining("permission denied");
    }

    @Test
    void evenTheOwnerCannotUpdateOrDeleteTheRecord() {
        for (String sql : List.of("UPDATE bonus.attachment_entry SET amount = 1", "DELETE FROM bonus.declaration_outcome",
                "DELETE FROM bonus.status_event", "UPDATE bonus.settlement SET terminal_amount = 0")) {
            // status_event and settlement start empty: a statement touching no row fires no ROW
            // trigger, so seed one of each first, or "append-only" would never be raised for them.
            assertThatCode(() -> seedOneOfEach()).doesNotThrowAnyException();
            assertThatThrownBy(() -> ownerRun(sql)).as(sql).hasMessageContaining("append-only");
        }
    }

    private static boolean seeded;

    private static synchronized void seedOneOfEach() throws Exception {
        if (seeded) return;
        ownerRun("INSERT INTO bonus.status_event (tenant_id, event_id, policy_number, status, effective_at) VALUES ('"
            + TENANT + "', gen_random_uuid(), 'POL-B1', 'ACTIVE', now())");
        ownerRun("INSERT INTO bonus.settlement (tenant_id, policy_number, exit_type, exit_ref, exit_date, attached_amount, "
            + "interim_amount, terminal_amount) VALUES ('" + TENANT + "', 'POL-B1', 'MATURITY', 'instalment:x', current_date, 0, 0, 0)");
        seeded = true;
    }

    @Test
    void anEntryWhoseTotalDoesNotFollowIsRefused() {
        assertThatThrownBy(() -> ownerRun(entry("POL-B1", 2, "REVERSIONARY", "1000.00", "999.00", "declaration:x:POL-B1", null)))
            .hasMessageContaining("is not 30000.00 + 1000.00");
    }

    @Test
    void aGapInTheSequenceIsRefused() {
        assertThatThrownBy(() -> ownerRun(entry("POL-B1", 5, "REVERSIONARY", "1000.00", "31000.00", "declaration:y:POL-B1", null)))
            .hasMessageContaining("has no entry 4 before it");
    }

    @Test
    void aDeclarationAttachesToAPolicyOnce() {
        assertThatThrownBy(() -> ownerRun(entry("POL-B1", 2, "REVERSIONARY", "1000.00", "31000.00", "declaration:" + DECL + ":POL-B1", null)))
            .hasMessageContaining("ux_attachment_source");
        assertThatThrownBy(() -> ownerRun("INSERT INTO bonus.declaration_outcome (tenant_id, declaration_id, policy_number, outcome) "
            + "VALUES ('" + TENANT + "', '" + DECL + "', 'POL-B1', 'NOTHING_DUE')")).hasMessageContaining("ux_declaration_outcome");
    }

    @Test
    void aNotEligibleOutcomeMustSayWhy() {
        assertThatThrownBy(() -> ownerRun("INSERT INTO bonus.declaration_outcome (tenant_id, declaration_id, policy_number, outcome) "
            + "VALUES ('" + TENANT + "', '" + DECL + "', 'POL-B2', 'NOT_ELIGIBLE')")).hasMessageContaining("outcome_reason_shape");
    }

    @Test
    void theProposerCannotApprove() {
        assertThatThrownBy(() -> ownerRun("INSERT INTO bonus.declaration (tenant_id, product_id, valuation_date, "
            + "reversionary_rate_percent, terminal_rate_percent, status, proposed_by, approved_by) VALUES ('" + TENANT
            + "', gen_random_uuid(), current_date, 3, 0, 'APPROVED', 'same', 'same')")).hasMessageContaining("declaration_two_person");
    }

    @Test
    void everyBonusTableHasRowLevelSecurity() throws Exception {
        try (Connection c = owner(); Statement s = c.createStatement();
             var rs = s.executeQuery("SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                 + "WHERE n.nspname = 'bonus' AND c.relkind = 'r' AND NOT c.relrowsecurity")) {
            List<String> unprotected = new ArrayList<>();
            while (rs.next()) unprotected.add(rs.getString(1));
            assertThat(unprotected).as("bonus tables without RLS").isEmpty();
        }
    }

    @Test
    void anotherTenantCannotSeeTheLedger() throws Exception {
        try (Connection c = appRole(UUID.randomUUID()); Statement s = c.createStatement();
             var rs = s.executeQuery("SELECT count(*) FROM bonus.attachment_entry")) {
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }
        // The control: the owning tenant does see it, so the zero is isolation, not an empty table.
        try (Connection c = appRole(TENANT); Statement s = c.createStatement();
             var rs = s.executeQuery("SELECT count(*) FROM bonus.attachment_entry")) {
            rs.next();
            assertThat(rs.getInt(1)).isPositive();
        }
    }

    @Test
    void aReversalThatFollowsIsAccepted() {
        assertThatCode(() -> ownerRun(entry("POL-B9", 2, "REVERSAL", "-30000.00", "0.00", "reversal:POL-B9:1", "1")))
            .doesNotThrowAnyException();
    }
}
