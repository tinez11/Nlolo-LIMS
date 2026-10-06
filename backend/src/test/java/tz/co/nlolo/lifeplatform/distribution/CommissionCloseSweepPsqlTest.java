package tz.co.nlolo.lifeplatform.distribution;

import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.YearMonth;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@code distribution.close_commission_statements()} directly against a bare
 * {@code postgres:16} container, mirroring {@code billing.BillingSweepPsqlTest}'s approach:
 * pg_cron is not installed there, so only the {@code CREATE OR REPLACE FUNCTION} half of
 * configure-commission-close.sql is applied, not the trailing {@code cron.schedule(...)} line.
 *
 * <p>The sweep is the whole reason closing is SQL rather than a Java {@code @Scheduled} method:
 * it is CROSS-TENANT, and a Java thread has no {@code TenantContext}, so RLS would show it zero
 * rows. {@link #closesOnlyPastPeriodStatementsAndDoesSoAcrossEveryTenant} is what actually pins
 * that property, by seeding two different tenants and requiring BOTH to close.
 *
 * <p>{@link #theScheduledCommandStringActuallyExecutes} covers the half that
 * {@code BillingSweepPsqlTest} left uncovered, and it is not a hypothetical gap -- see that test's
 * own javadoc.
 */
@Testcontainers
class CommissionCloseSweepPsqlTest {

    private static final Path SWEEP_FILE = Path.of("db-migrations/_post-migration/configure-commission-close.sql");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @BeforeAll
    static void applyMigrationsAndCloseFunction() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/distribution/V1__create_distribution_schema.sql",
            "db-migrations/distribution/V2__grants_rls_money_checks_projection_and_statement_lifecycle.sql",
            "db-migrations/distribution/V5__agent_channel_and_home_branch.sql",
            "db-migrations/distribution/V6__commission_withholding.sql");

        String fullFile = Files.readString(SWEEP_FILE);
        String functionOnly = fullFile.substring(0, fullFile.indexOf("-- Daily at 01:00"));
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(functionOnly);
        }
    }

    /**
     * Postgres grants EXECUTE on a newly created function to PUBLIC by default -- unlike tables,
     * where a bare CREATE grants nothing -- so without an explicit REVOKE, app_role could call
     * this SECURITY DEFINER function directly despite being NOSUPERUSER NOBYPASSRLS, which is
     * exactly the request-path privilege escalation the function's own header comment says must
     * never be possible. Verified empirically against a real image before this REVOKE existed:
     * {@code has_function_privilege('app_role', ...)} answered true. app_role is not bootstrapped
     * as a role in this bare-postgres container (only MigrationTestSupport's placeholder), so this
     * checks PUBLIC directly -- REVOKE ... FROM PUBLIC is what an app_role-specific check would
     * also depend on, and PUBLIC is the actual mechanism that was silently granting the access.
     */
    @Test
    void appRoleHasNoExecutePrivilegeOnTheCloseFunction() throws Exception {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement select = connection.prepareStatement(
                 "SELECT has_function_privilege('public', 'distribution.close_commission_statements()', 'EXECUTE')")) {
            try (ResultSet rs = select.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1))
                    .as("PUBLIC (and therefore app_role) must not be able to execute this SECURITY DEFINER function directly")
                    .isFalse();
            }
        }
    }

    /** An agent_profile row is required: commission_statement.agent_id is a real FK. */
    private static UUID seedAgent(Connection connection, UUID tenantId, String tag) throws Exception {
        UUID agentId = UUID.randomUUID();
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO distribution.agent_profile (agent_id, tenant_id, party_id, license_number, license_expiry_date) "
                + "VALUES (?, ?, ?, ?, CURRENT_DATE + 365)")) {
            insert.setObject(1, agentId);
            insert.setObject(2, tenantId);
            insert.setObject(3, UUID.randomUUID());
            insert.setString(4, "LIC-SWEEP-" + tag);
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }
        return agentId;
    }

    private static UUID seedOpenStatement(Connection connection, UUID tenantId, UUID agentId, String period) throws Exception {
        UUID statementId = UUID.randomUUID();
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO distribution.commission_statement (statement_id, tenant_id, agent_id, period, total_amount, "
                + "total_currency, status) VALUES (?, ?, ?, ?, 10000.00, 'TZS', 'OPEN')")) {
            insert.setObject(1, statementId);
            insert.setObject(2, tenantId);
            insert.setObject(3, agentId);
            insert.setString(4, period);
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }
        return statementId;
    }

    private record StatementState(String status, boolean closedAtSet) {}

    private static StatementState stateOf(Connection connection, UUID statementId) throws Exception {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT status, closed_at FROM distribution.commission_statement WHERE statement_id = ?")) {
            select.setObject(1, statementId);
            try (ResultSet rs = select.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return new StatementState(rs.getString("status"), rs.getTimestamp("closed_at") != null);
            }
        }
    }

    @Test
    void closesOnlyPastPeriodStatementsAndDoesSoAcrossEveryTenant() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        String currentPeriod = YearMonth.now().toString();
        String pastPeriod = YearMonth.now().minusMonths(1).toString();

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            UUID agentA = seedAgent(connection, tenantA, "A");
            UUID agentB = seedAgent(connection, tenantB, "B");
            UUID pastA = seedOpenStatement(connection, tenantA, agentA, pastPeriod);
            UUID pastB = seedOpenStatement(connection, tenantB, agentB, pastPeriod);
            UUID currentA = seedOpenStatement(connection, tenantA, agentA, currentPeriod);

            // NEGATIVE CONTROL: everything is genuinely OPEN before the sweep, so a sweep that did
            // nothing at all could not pass the assertions below.
            for (UUID id : new UUID[] {pastA, pastB, currentA}) {
                assertThat(stateOf(connection, id).status()).isEqualTo("OPEN");
            }

            try (Statement sweep = connection.createStatement()) {
                sweep.execute("SELECT distribution.close_commission_statements()");
            }

            // BOTH tenants closed -- this is the assertion that proves the function is genuinely
            // cross-tenant, which is the entire reason closing is SQL and not Java.
            assertThat(stateOf(connection, pastA)).isEqualTo(new StatementState("CLOSED", true));
            assertThat(stateOf(connection, pastB)).isEqualTo(new StatementState("CLOSED", true));
            // The current period is still accruing and must be left alone.
            assertThat(stateOf(connection, currentA)).isEqualTo(new StatementState("OPEN", false));
        }
    }

    @Test
    void reRunningTheSweepIsANoOpAndNeverRewritesClosedAt() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String pastPeriod = YearMonth.now().minusMonths(2).toString();

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            UUID agentId = seedAgent(connection, tenantId, "IDEMPOTENT");
            UUID statementId = seedOpenStatement(connection, tenantId, agentId, pastPeriod);

            try (Statement sweep = connection.createStatement()) {
                sweep.execute("SELECT distribution.close_commission_statements()");
            }
            java.sql.Timestamp firstClosedAt;
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT closed_at FROM distribution.commission_statement WHERE statement_id = ?")) {
                select.setObject(1, statementId);
                try (ResultSet rs = select.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    firstClosedAt = rs.getTimestamp("closed_at");
                }
            }
            assertThat(firstClosedAt).isNotNull();

            // The daily cadence means this runs ~30 times over a boundary crossed once, so the
            // no-op property is load-bearing, not incidental. closed_at must not drift either: a
            // predicate written on `period` alone (without status = 'OPEN') would still "work"
            // but would rewrite closed_at on every run, quietly destroying the real close date.
            try (Statement sweep = connection.createStatement()) {
                sweep.execute("SELECT distribution.close_commission_statements()");
            }
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT status, closed_at FROM distribution.commission_statement WHERE statement_id = ?")) {
                select.setObject(1, statementId);
                try (ResultSet rs = select.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("status")).isEqualTo("CLOSED");
                    assertThat(rs.getTimestamp("closed_at")).isEqualTo(firstClosedAt);
                }
            }
        }
    }

    @Test
    void aStatementAlreadyPastOpenIsNeverDraggedBackwards() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String pastPeriod = YearMonth.now().minusMonths(1).toString();

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            UUID agentId = seedAgent(connection, tenantId, "PAID");
            UUID statementId = UUID.randomUUID();
            // A past-period statement that has already been paid out. The sweep's period predicate
            // matches it; only the status predicate keeps it safe.
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO distribution.commission_statement (statement_id, tenant_id, agent_id, period, total_amount, "
                    + "total_currency, status, paid_at) VALUES (?, ?, ?, ?, 10000.00, 'TZS', 'PAID', now())")) {
                insert.setObject(1, statementId);
                insert.setObject(2, tenantId);
                insert.setObject(3, agentId);
                insert.setString(4, pastPeriod);
                assertThat(insert.executeUpdate()).isEqualTo(1);
            }

            try (Statement sweep = connection.createStatement()) {
                sweep.execute("SELECT distribution.close_commission_statements()");
            }

            assertThat(stateOf(connection, statementId).status())
                .as("a PAID statement must never be reopened or re-closed by the sweep")
                .isEqualTo("PAID");
        }
    }

    /**
     * Executes the EXACT command string pg_cron is registered to run, parsed out of the real
     * post-migration file rather than retyped here -- so the thing under test is the thing that
     * ships.
     *
     * <p>This closes a gap that was not hypothetical. {@code configure-billing-sweep.sql} shipped
     * in M4 scheduling {@code CALL billing.sweep_billing_state()}, and that function is a FUNCTION,
     * not a PROCEDURE: Postgres answers {@code CALL} on a function with "is not a procedure. HINT:
     * To call a function, use SELECT." So billing's sweep failed on every 15-minute tick from M4
     * until M7 found it. Three things hid it -- the idiom was copied from
     * configure-pg-partman.sql where {@code CALL} is correct (partman.run_maintenance_proc really
     * is a procedure), {@code BillingSweepPsqlTest} invokes the function directly with SELECT and
     * strips the schedule line, and {@code pg_cron_job_failed_total} has no producer so a job
     * failing forever alerts nobody.
     *
     * <p>Executing the parsed string is what makes this non-vacuous: a future edit swapping SELECT
     * back to CALL fails here immediately, without needing pg_cron installed in the container.
     */
    @Test
    void theScheduledCommandStringActuallyExecutes() throws Exception {
        String fullFile = Files.readString(SWEEP_FILE);
        Matcher matcher = Pattern.compile("cron\\.schedule\\([^$]*\\$\\$(.*?)\\$\\$", Pattern.DOTALL).matcher(fullFile);
        assertThat(matcher.find())
            .as("configure-commission-close.sql must register a cron job with a $$-quoted command")
            .isTrue();
        String scheduledCommand = matcher.group(1).trim();

        // Guards against the regex silently matching something harmless if the file is restructured.
        assertThat(scheduledCommand).contains("distribution.close_commission_statements()");

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            // Fails with "is not a procedure" if this is ever changed back to CALL.
            statement.execute(scheduledCommand);
        }
    }
}
