package tz.co.nlolo.lifeplatform.finaccounting;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ledger's invariants, enforced by the database itself (finaccounting V10, IFRS 17 I1): a journal balances at
 * commit, nothing posted is ever changed, no line joins a journal posted earlier, an account's mode decides who may
 * post to it, a heading takes nothing, a locked period takes nothing and a closing one takes no events, a manual
 * journal names two people, and an approved accounting policy election is never edited. Plain JDBC as the table
 * owner, against the finaccounting migrations alone.
 */
@Testcontainers
class LedgerGuardsIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    private static final UUID TENANT = UUID.randomUUID();
    private static final String PERIOD = "2026-10";

    @BeforeAll
    static void migrateAndSeedAccounts() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/finaccounting/V1__create_finaccounting_schema.sql",
            "db-migrations/finaccounting/V2__grants_rls_chart_of_accounts_journal_entry_and_posting_columns.sql",
            "db-migrations/finaccounting/V3__account_code_foreign_key.sql",
            "db-migrations/finaccounting/V4__chart_of_account_writable_via_api.sql",
            "db-migrations/finaccounting/V5__chart_of_account_hierarchy.sql",
            "db-migrations/finaccounting/V7__q4_2026_partitions.sql",
            "db-migrations/finaccounting/V10__ifrs17_ledger_foundation.sql",
            "db-migrations/finaccounting/V11__groups_and_policy_classification.sql",
            "db-migrations/finaccounting/V12__unposted_events_and_paa_earning.sql",
            "db-migrations/finaccounting/V13__disbursement_method.sql",
            "db-migrations/finaccounting/V14__manual_journals.sql");
        try (Connection c = connect()) {
            account(c, "2000", "LIABILITY", "CR", "MAN", false, null);
            account(c, "2120", "LIABILITY", "CR", "MAN", false, "2000");
            account(c, "2121", "LIABILITY", "CR", "AUTO", true, "2120");
            account(c, "2122", "LIABILITY", "DR", "AUTO", true, "2120");
            account(c, "1000", "ASSET", "DR", "MAN", false, null);
            account(c, "1110", "ASSET", "DR", "BOTH", true, "1000");
            account(c, "3000", "EQUITY", "CR", "MAN", false, null);
            account(c, "3110", "EQUITY", "CR", "MAN", true, "3000");
            account(c, "3210", "EQUITY", "CR", "MAN", true, "3000");
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void account(Connection c, String code, String type, String normal, String mode, boolean posting,
                                String parent) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO finaccounting.chart_of_account (tenant_id, account_code,"
                + " name, account_type, normal_balance, posting_mode, posting_allowed, parent_code, level, currency, created_by)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'TZS', 'test')")) {
            ps.setObject(1, TENANT);
            ps.setString(2, code);
            ps.setString(3, "Account " + code);
            ps.setString(4, type);
            ps.setString(5, normal);
            ps.setString(6, mode);
            ps.setBoolean(7, posting);
            ps.setString(8, parent);
            ps.setShort(9, (short) (parent == null ? 1 : 2));
            ps.executeUpdate();
        }
    }

    /** One journal and its lines in ONE transaction, committed. */
    private static UUID post(String source, String period, String preparer, String approver, String reasonCode,
                             String... lines) throws SQLException {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                UUID id = journal(c, source, period, preparer, approver, reasonCode);
                for (String l : lines) {
                    String[] p = l.split(" ");   // "DR 2122 100.00"
                    line(c, id, p[1], p[0], p[2]);
                }
                c.commit();
                return id;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    private static UUID journal(Connection c, String source, String period, String preparer, String approver,
                                String reasonCode) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO finaccounting.journal_entry (journal_entry_id, tenant_id,"
                + " source_event, source_ref, period, source_type, preparer, approver, reason, reason_code)"
                + " VALUES (?, ?, 'test.Event', ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setObject(2, TENANT);
            ps.setString(3, id.toString());
            ps.setString(4, period);
            ps.setString(5, source);
            ps.setString(6, preparer);
            ps.setString(7, approver);
            ps.setString(8, preparer == null ? null : "test");
            ps.setString(9, reasonCode);
            ps.executeUpdate();
        }
        return id;
    }

    private static void line(Connection c, UUID journal, String account, String direction, String amount)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO finaccounting.gl_posting (tenant_id, journal_entry_id,"
                + " account_code, direction, amount, currency, period, posting_type, source_event, source_ref)"
                + " SELECT ?, ?, ?, ?, ?::numeric, 'TZS', period, 'IFRS17', source_event, source_ref"
                + " FROM finaccounting.journal_entry WHERE journal_entry_id = ?")) {
            ps.setObject(1, TENANT);
            ps.setObject(2, journal);
            ps.setString(3, account);
            ps.setString(4, direction);
            ps.setString(5, amount);
            ps.setObject(6, journal);
            ps.executeUpdate();
        }
    }

    private static void sql(String statement) throws SQLException {
        try (Connection c = connect(); var s = c.createStatement()) {
            s.execute(statement);
        }
    }

    @Test
    void aBalancedJournalPosts() {
        assertThatCode(() -> post("EVENT", PERIOD, null, null, null, "DR 2122 100.00", "CR 2121 100.00"))
            .doesNotThrowAnyException();
    }

    @Test
    void aJournalWhoseLinesDoNotBalanceIsRefusedAtCommit() {
        assertThatThrownBy(() -> post("EVENT", PERIOD, null, null, null, "DR 2122 100.00", "CR 2121 90.00"))
            .hasMessageContaining("LEDGER_UNBALANCED");
        assertThatThrownBy(() -> post("EVENT", PERIOD, null, null, null))   // no lines at all
            .hasMessageContaining("LEDGER_UNBALANCED");
    }

    @Test
    void aPostedLineOrJournalIsNeverUpdatedOrDeleted() throws Exception {
        UUID id = post("EVENT", PERIOD, null, null, null, "DR 2122 50.00", "CR 2121 50.00");
        assertThatThrownBy(() -> sql("UPDATE finaccounting.gl_posting SET amount = 1 WHERE journal_entry_id = '" + id + "'"))
            .hasMessageContaining("LEDGER_IMMUTABLE");
        assertThatThrownBy(() -> sql("DELETE FROM finaccounting.gl_posting WHERE journal_entry_id = '" + id + "'"))
            .hasMessageContaining("LEDGER_IMMUTABLE");
        assertThatThrownBy(() -> sql("UPDATE finaccounting.journal_entry SET reason = 'x' WHERE journal_entry_id = '" + id + "'"))
            .hasMessageContaining("LEDGER_IMMUTABLE");
    }

    @Test
    void aLineCannotBeAddedToAJournalPostedEarlier() throws Exception {
        UUID id = post("EVENT", PERIOD, null, null, null, "DR 2122 70.00", "CR 2121 70.00");
        assertThatThrownBy(() -> {
            try (Connection c = connect()) {
                line(c, id, "2122", "DR", "1.00");
            }
        }).hasMessageContaining("LEDGER_SEALED");
    }

    @Test
    void anAccountsModeDecidesWhoMayPostToIt() {
        // AUTO refuses a manual journal; MAN refuses an event.
        assertThatThrownBy(() -> post("MANUAL", PERIOD, "fin-a", "fin-b", null, "DR 2122 10.00", "CR 2121 10.00"))
            .hasMessageContaining("LEDGER_MODE");
        assertThatThrownBy(() -> post("EVENT", PERIOD, null, null, null, "DR 1110 10.00", "CR 3110 10.00"))
            .hasMessageContaining("LEDGER_MODE");
        // BOTH takes a manual line only with a reason code.
        assertThatThrownBy(() -> post("MANUAL", PERIOD, "fin-a", "fin-b", null, "DR 1110 10.00", "CR 3110 10.00"))
            .hasMessageContaining("needs a reason code");
        assertThatCode(() -> post("MANUAL", PERIOD, "fin-a", "fin-b", "CORRECTION", "DR 1110 10.00", "CR 3110 10.00"))
            .doesNotThrowAnyException();
        // The platform's own runs may post to every mode (I1 R1).
        assertThatCode(() -> post("SYSTEM", PERIOD, null, null, null, "DR 2122 5.00", "CR 3210 5.00"))
            .doesNotThrowAnyException();
    }

    @Test
    void aHeadingTakesNoPostings() {
        assertThatThrownBy(() -> post("EVENT", PERIOD, null, null, null, "DR 2122 10.00", "CR 2120 10.00"))
            .hasMessageContaining("LEDGER_HEADING");
    }

    @Test
    void aLockedPeriodTakesNothingAndAClosingOneTakesNoEvents() throws Exception {
        sql("INSERT INTO finaccounting.accounting_period (tenant_id, period, status) VALUES ('" + TENANT + "', '2026-01', 'LOCKED')");
        sql("INSERT INTO finaccounting.accounting_period (tenant_id, period, status) VALUES ('" + TENANT + "', '2026-02', 'CLOSING')");
        assertThatThrownBy(() -> post("SYSTEM", "2026-01", null, null, null, "DR 2122 10.00", "CR 2121 10.00"))
            .hasMessageContaining("LEDGER_PERIOD_LOCKED");
        assertThatThrownBy(() -> post("EVENT", "2026-02", null, null, null, "DR 2122 10.00", "CR 2121 10.00"))
            .hasMessageContaining("LEDGER_PERIOD_CLOSING");
        assertThatCode(() -> post("SYSTEM", "2026-02", null, null, null, "DR 2122 10.00", "CR 2121 10.00"))
            .doesNotThrowAnyException();
    }

    /**
     * The lock cannot overtake a journal already writing to the period: the guard reads the period row FOR SHARE, so
     * the close action's FOR UPDATE waits for that journal (and its clearing check then sees it). Without the share
     * lock, a journal begun while the period was open committed into a locked one.
     */
    @Test
    void aPeriodCannotBeLockedUnderAJournalStillBeingWritten() throws Exception {
        sql("INSERT INTO finaccounting.accounting_period (tenant_id, period, status) VALUES ('" + TENANT + "', '2026-03', 'CLOSING')");
        try (Connection writer = connect(); Connection closer = connect()) {
            writer.setAutoCommit(false);
            UUID id = journal(writer, "SYSTEM", "2026-03", null, null, null);
            line(writer, id, "2122", "DR", "10.00");
            line(writer, id, "2121", "CR", "10.00");

            closer.setAutoCommit(false);
            assertThatThrownBy(() -> {
                try (var s = closer.createStatement()) {
                    s.execute("SELECT status FROM finaccounting.accounting_period WHERE tenant_id = '" + TENANT
                        + "' AND period = '2026-03' FOR UPDATE NOWAIT");
                }
            }).hasMessageContaining("could not obtain lock");
            closer.rollback();

            writer.commit();
            try (var s = closer.createStatement()) {
                s.execute("SELECT status FROM finaccounting.accounting_period WHERE tenant_id = '" + TENANT
                    + "' AND period = '2026-03' FOR UPDATE NOWAIT");   // free once the journal is in
            }
            closer.rollback();
        }
    }

    @Test
    void aManualJournalNeedsTwoPeopleAndAReason() {
        assertThatThrownBy(() -> post("MANUAL", PERIOD, "fin-a", "fin-a", "CORRECTION", "DR 1110 10.00", "CR 3110 10.00"))
            .hasMessageContaining("journal_manual_has_people");
    }

    @Test
    void anApprovedElectionIsNeverEdited() throws Exception {
        UUID id = UUID.randomUUID();
        sql("INSERT INTO finaccounting.accounting_policy_election (election_id, tenant_id, election_key, scope, election_value,"
            + " effective_from, status, sign_off_ref, proposed_by, proposed_at, decided_by, decided_at, register_version)"
            + " VALUES ('" + id + "', '" + TENANT + "', 'OCI_OPTION', '*', 'OFF', DATE '2020-01-01', 'APPROVED', 'memo',"
            + " 'a', now(), 'b', now(), 1)");
        assertThatThrownBy(() -> sql("UPDATE finaccounting.accounting_policy_election SET election_value = 'ON'"
            + " WHERE election_id = '" + id + "'")).hasMessageContaining("POLICY_ELECTION_IMMUTABLE");
        assertThatThrownBy(() -> sql("DELETE FROM finaccounting.accounting_policy_election WHERE election_id = '" + id + "'"))
            .hasMessageContaining("POLICY_ELECTION_IMMUTABLE");
        assertThat(id).isNotNull();
    }
}
