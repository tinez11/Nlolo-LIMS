package tz.co.nlolo.lifeplatform.billing;

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
import java.time.LocalDate;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves billing.sweep_billing_state()'s own transition logic directly against a bare
 * postgres:16 container -- pg_cron is not installed there, so only the CREATE OR REPLACE
 * FUNCTION statement from configure-billing-sweep.sql is applied, not its trailing
 * cron.schedule(...) line.
 *
 * <p><b>This class's javadoc used to claim "Task 8 separately verifies the real cron.schedule(...)
 * registration against the actual infra/postgres/Dockerfile image". That was false</b> -- no test
 * anywhere in this repository referenced cron.schedule, and M4's verification confirmed only that
 * the job REGISTERS, which pg_cron will happily do for a command string it can never execute. The
 * cost of the false claim was three milestones of a broken sweep: the registered command was
 * {@code CALL billing.sweep_billing_state()}, and CALL is for PROCEDUREs, so every 15-minute tick
 * failed with "is not a procedure". Fixed to SELECT in M7, and
 * {@link #theScheduledCommandStringActuallyExecutes} now pins it here.
 */
@Testcontainers
class BillingSweepPsqlTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @BeforeAll
    static void applyMigrationsAndSweepFunction() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/refdata/V3__seed_billing_parameters.sql",
            "db-migrations/billing/V1__create_billing_schema.sql",
            "db-migrations/billing/V2__grants_rls_money_checks_and_notification_columns.sql");

        String fullFile = Files.readString(Path.of("db-migrations/_post-migration/configure-billing-sweep.sql"));
        String functionOnly = fullFile.substring(0, fullFile.indexOf("-- Every 15 minutes"));
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(functionOnly);
        }
    }

    /**
     * Executes the EXACT command string pg_cron is registered to run, parsed out of
     * configure-billing-sweep.sql rather than retyped, so the thing under test is the thing that
     * ships. Needs no pg_cron in the container: what broke was the command string itself, not the
     * scheduling. Reverting that line to CALL fails this immediately with "is not a procedure".
     */
    @Test
    void theScheduledCommandStringActuallyExecutes() throws Exception {
        String fullFile = Files.readString(Path.of("db-migrations/_post-migration/configure-billing-sweep.sql"));
        Matcher matcher = Pattern.compile("cron\\.schedule\\([^$]*\\$\\$(.*?)\\$\\$", Pattern.DOTALL).matcher(fullFile);
        assertThat(matcher.find())
            .as("configure-billing-sweep.sql must register a cron job with a $$-quoted command")
            .isTrue();
        String scheduledCommand = matcher.group(1).trim();
        assertThat(scheduledCommand).contains("billing.sweep_billing_state()");

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(scheduledCommand);
        }
    }

    @Test
    void sweepBillingStateTransitionsOverdueInvoicesAndEscalatesDunning() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID scheduleId = UUID.randomUUID();
        UUID overdueInvoiceId = UUID.randomUUID();
        UUID deepArrearsInvoiceId = UUID.randomUUID();

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (PreparedStatement insertSchedule = connection.prepareStatement(
                    "INSERT INTO billing.billing_schedule (billing_schedule_id, tenant_id, policy_number, premium_frequency, premium_amount) " +
                    "VALUES (?, ?, 'BILLING-SWEEP-01', 'MONTHLY', 15000.00)")) {
                insertSchedule.setObject(1, scheduleId);
                insertSchedule.setObject(2, tenantId);
                assertThat(insertSchedule.executeUpdate()).isEqualTo(1);
            }
            // Invoice 1: grace period ended 1 day ago -- should become OVERDUE with a fresh
            // arrears_case at dunning_level=1 on the first sweep.
            try (PreparedStatement insertInvoice1 = connection.prepareStatement(
                    "INSERT INTO billing.premium_invoice (invoice_id, tenant_id, billing_schedule_id, policy_number, due_date, amount, " +
                    "grace_period_ends_at, status) VALUES (?, ?, ?, 'BILLING-SWEEP-01', ?, 15000.00, ?, 'DUE')")) {
                insertInvoice1.setObject(1, overdueInvoiceId);
                insertInvoice1.setObject(2, tenantId);
                insertInvoice1.setObject(3, scheduleId);
                insertInvoice1.setObject(4, LocalDate.now().minusDays(20));
                insertInvoice1.setObject(5, LocalDate.now().minusDays(1));
                assertThat(insertInvoice1.executeUpdate()).isEqualTo(1);
            }
            // Invoice 2: an ArrearsCase already open for 40 days -- beyond LEVEL_5's 30-day
            // threshold -- proving the sweep can jump straight to level 5 in one call, not just
            // escalate sequentially one level per sweep.
            try (PreparedStatement insertInvoice2 = connection.prepareStatement(
                    "INSERT INTO billing.premium_invoice (invoice_id, tenant_id, billing_schedule_id, policy_number, due_date, amount, " +
                    "grace_period_ends_at, status) VALUES (?, ?, ?, 'BILLING-SWEEP-02', ?, 15000.00, ?, 'OVERDUE')")) {
                insertInvoice2.setObject(1, deepArrearsInvoiceId);
                insertInvoice2.setObject(2, tenantId);
                insertInvoice2.setObject(3, scheduleId);
                insertInvoice2.setObject(4, LocalDate.now().minusDays(50));
                insertInvoice2.setObject(5, LocalDate.now().minusDays(40));
                assertThat(insertInvoice2.executeUpdate()).isEqualTo(1);
            }
            try (PreparedStatement insertArrears = connection.prepareStatement(
                    "INSERT INTO billing.arrears_case (tenant_id, invoice_id, policy_number, dunning_level, opened_at) " +
                    "VALUES (?, ?, 'BILLING-SWEEP-02', 1, now() - interval '40 days')")) {
                insertArrears.setObject(1, tenantId);
                insertArrears.setObject(2, deepArrearsInvoiceId);
                assertThat(insertArrears.executeUpdate()).isEqualTo(1);
            }

            // NEGATIVE CONTROL: assert the pre-sweep state first, proving the test doesn't pass
            // vacuously against seed data already in the post-sweep state by coincidence.
            try (PreparedStatement preCheck = connection.prepareStatement(
                    "SELECT status FROM billing.premium_invoice WHERE invoice_id = ?")) {
                preCheck.setObject(1, overdueInvoiceId);
                try (ResultSet rs = preCheck.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).isEqualTo("DUE");
                }
            }

            try (Statement sweep = connection.createStatement()) {
                sweep.execute("SELECT billing.sweep_billing_state()");
            }

            try (PreparedStatement postCheck1 = connection.prepareStatement(
                    "SELECT pi.status, ac.dunning_level FROM billing.premium_invoice pi " +
                    "JOIN billing.arrears_case ac ON ac.invoice_id = pi.invoice_id AND ac.resolved_at IS NULL " +
                    "WHERE pi.invoice_id = ?")) {
                postCheck1.setObject(1, overdueInvoiceId);
                try (ResultSet rs = postCheck1.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).isEqualTo("OVERDUE");
                    assertThat(rs.getInt(2)).isEqualTo(1);
                }
            }

            try (PreparedStatement postCheck2 = connection.prepareStatement(
                    "SELECT dunning_level FROM billing.arrears_case WHERE invoice_id = ? AND resolved_at IS NULL")) {
                postCheck2.setObject(1, deepArrearsInvoiceId);
                try (ResultSet rs = postCheck2.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(5);
                }
            }
        }
    }

    /**
     * Proves item 5 of sweep_billing_state() -- the OFFLINE_RECEIPT_SLA_HOURS breach -- at the
     * SQL level. Task 5's Java-side test (BillingApiIntegrationTest
     * .capturingAFieldReceiptThenSweepingAfterTheSlaWindowPublishesReconciliationOverdue) seeds
     * RECONCILIATION_OVERDUE directly rather than letting the real sweep produce it, explicitly
     * deferring that proof to this class; this is the test that actually closes it, exercising
     * refdata.reference_code_set's real seeded OFFLINE_RECEIPT_SLA_HOURS/DEFAULT value (24h) via
     * the sweep function's own subquery rather than a hardcoded interval literal.
     */
    @Test
    void sweepBillingStateFlipsAStaleFieldReceiptToReconciliationOverdue() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID overdueReceiptId = UUID.randomUUID();
        UUID freshReceiptId = UUID.randomUUID();

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            // Captured 25 hours ago -- past the seeded 24-hour SLA -- must flip to
            // RECONCILIATION_OVERDUE.
            try (PreparedStatement insertOverdue = connection.prepareStatement(
                    "INSERT INTO billing.field_receipt (receipt_id, tenant_id, policy_number, agent_id, amount, " +
                    "client_idempotency_key, captured_at_client, captured_at_server) " +
                    "VALUES (?, ?, 'SWEEP-RECEIPT-01', ?, 15000.00, ?, now() - interval '25 hours', now() - interval '25 hours')")) {
                insertOverdue.setObject(1, overdueReceiptId);
                insertOverdue.setObject(2, tenantId);
                insertOverdue.setObject(3, UUID.randomUUID());
                insertOverdue.setString(4, "sweep-test-overdue-" + overdueReceiptId);
                assertThat(insertOverdue.executeUpdate()).isEqualTo(1);
            }
            // Captured 1 hour ago -- well within the SLA -- the NEGATIVE CONTROL proving the
            // sweep doesn't just flip every PENDING_RECONCILIATION row regardless of age.
            try (PreparedStatement insertFresh = connection.prepareStatement(
                    "INSERT INTO billing.field_receipt (receipt_id, tenant_id, policy_number, agent_id, amount, " +
                    "client_idempotency_key, captured_at_client, captured_at_server) " +
                    "VALUES (?, ?, 'SWEEP-RECEIPT-02', ?, 15000.00, ?, now() - interval '1 hour', now() - interval '1 hour')")) {
                insertFresh.setObject(1, freshReceiptId);
                insertFresh.setObject(2, tenantId);
                insertFresh.setObject(3, UUID.randomUUID());
                insertFresh.setString(4, "sweep-test-fresh-" + freshReceiptId);
                assertThat(insertFresh.executeUpdate()).isEqualTo(1);
            }

            try (PreparedStatement preCheck = connection.prepareStatement(
                    "SELECT status FROM billing.field_receipt WHERE receipt_id = ?")) {
                preCheck.setObject(1, overdueReceiptId);
                try (ResultSet rs = preCheck.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).isEqualTo("PENDING_RECONCILIATION");
                }
            }

            try (Statement sweep = connection.createStatement()) {
                sweep.execute("SELECT billing.sweep_billing_state()");
            }

            try (PreparedStatement postCheckOverdue = connection.prepareStatement(
                    "SELECT status FROM billing.field_receipt WHERE receipt_id = ?")) {
                postCheckOverdue.setObject(1, overdueReceiptId);
                try (ResultSet rs = postCheckOverdue.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).isEqualTo("RECONCILIATION_OVERDUE");
                }
            }

            try (PreparedStatement postCheckFresh = connection.prepareStatement(
                    "SELECT status FROM billing.field_receipt WHERE receipt_id = ?")) {
                postCheckFresh.setObject(1, freshReceiptId);
                try (ResultSet rs = postCheckFresh.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).isEqualTo("PENDING_RECONCILIATION");
                }
            }

            // Also proves the metrics snapshot the OverdueMetricsGauge reads was really
            // refreshed by this same sweep call, not left at its migration-seeded 0.
            try (PreparedStatement metricsCheck = connection.prepareStatement(
                    "SELECT field_receipt_overdue_count FROM billing.overdue_metrics_snapshot WHERE id = 1")) {
                try (ResultSet rs = metricsCheck.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
            }
        }
    }

    /**
     * M5 regression: RECONCILED is a new terminal status (Task 7's
     * {@code FieldReceipt.reconcile()}) that step 5's escalation UPDATE must never touch. The
     * function's own WHERE clause already reads {@code status = 'PENDING_RECONCILIATION'} --
     * this test proves that guard genuinely excludes a reconciled receipt rather than merely
     * asserting the SQL text looks right, using the same negative-control-then-sweep idiom as
     * {@link #sweepBillingStateFlipsAStaleFieldReceiptToReconciliationOverdue}.
     */
    @Test
    void sweepBillingStateNeverEscalatesAReconciledFieldReceipt() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID reconciledReceiptId = UUID.randomUUID();

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            // Captured 48 hours ago (well past the 24-hour SLA) but already RECONCILED --
            // without the WHERE clause's exclusion this row would otherwise be exactly the kind
            // sweepBillingStateFlipsAStaleFieldReceiptToReconciliationOverdue proves DOES escalate.
            try (PreparedStatement insertReconciled = connection.prepareStatement(
                    "INSERT INTO billing.field_receipt (receipt_id, tenant_id, policy_number, agent_id, amount, " +
                    "client_idempotency_key, captured_at_client, captured_at_server, status, reconciled_at) " +
                    "VALUES (?, ?, 'SWEEP-RECEIPT-03', ?, 15000.00, ?, now() - interval '48 hours', now() - interval '48 hours', " +
                    "'RECONCILED', now() - interval '47 hours')")) {
                insertReconciled.setObject(1, reconciledReceiptId);
                insertReconciled.setObject(2, tenantId);
                insertReconciled.setObject(3, UUID.randomUUID());
                insertReconciled.setString(4, "sweep-test-reconciled-" + reconciledReceiptId);
                assertThat(insertReconciled.executeUpdate()).isEqualTo(1);
            }

            // NEGATIVE CONTROL: assert the pre-sweep state first.
            try (PreparedStatement preCheck = connection.prepareStatement(
                    "SELECT status FROM billing.field_receipt WHERE receipt_id = ?")) {
                preCheck.setObject(1, reconciledReceiptId);
                try (ResultSet rs = preCheck.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).isEqualTo("RECONCILED");
                }
            }

            try (Statement sweep = connection.createStatement()) {
                sweep.execute("SELECT billing.sweep_billing_state()");
            }

            try (PreparedStatement postCheck = connection.prepareStatement(
                    "SELECT status FROM billing.field_receipt WHERE receipt_id = ?")) {
                postCheck.setObject(1, reconciledReceiptId);
                try (ResultSet rs = postCheck.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    // The whole point: still RECONCILED, never escalated to RECONCILIATION_OVERDUE.
                    assertThat(rs.getString(1)).isEqualTo("RECONCILED");
                }
            }
        }
    }
}
