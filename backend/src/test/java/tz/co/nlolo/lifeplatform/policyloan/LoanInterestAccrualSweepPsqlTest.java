package tz.co.nlolo.lifeplatform.policyloan;

import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@code policyloan.accrue_loan_interest()} directly against a bare {@code postgres:16}
 * container, mirroring {@code billing.BillingSweepPsqlTest} and
 * {@code distribution.CommissionCloseSweepPsqlTest}: pg_cron is not installed there, so only the
 * {@code CREATE OR REPLACE FUNCTION} half of configure-loan-interest-accrual.sql is applied, not
 * the trailing {@code cron.schedule(...)} line -- except in
 * {@link #theScheduledCommandStringActuallyExecutes}, which parses and runs the scheduled command
 * string itself.
 *
 * <p><b>What this closes.</b> Interest was never accrued on a policy loan. The rate was resolved
 * from refdata at origination, persisted effective-dated into {@code loan_interest_term}, exposed
 * as {@code LoanView.currentInterestRate}, and folded into the outstanding balance by
 * {@code PolicyLoanApiImpl.computeOutstandingBalance} -- but no producer ever wrote an
 * {@code INTEREST_ACCRUAL} ledger entry, so an outstanding balance stayed flat forever and
 * {@code docs/01-domain-map.md:224}'s Forced Lapse ("loan balance plus interest exceeds cash
 * value") could not fire. Every assertion below therefore starts from a NEGATIVE CONTROL that the
 * ledger holds no accrual, so a sweep that did nothing at all could not pass.
 *
 * <p>{@link #accruesForEveryTenantAndOnlyForDisbursedOrRepayingLoans} is the test that pins the
 * cross-tenant property -- the entire reason accrual is SQL rather than a Java {@code @Scheduled}
 * method, since a Java thread has no {@code TenantContext} and RLS would show it zero rows.
 */
@Testcontainers
class LoanInterestAccrualSweepPsqlTest {

    private static final Path SWEEP_FILE = Path.of("db-migrations/_post-migration/configure-loan-interest-accrual.sql");

    /** Matches refdata V2's seeded TZ_POLICY_LOAN_ANNUAL_INTEREST_RATE placeholder ('12.0'). */
    private static final BigDecimal RATE_PERCENT = new BigDecimal("12.0000");
    private static final BigDecimal PRINCIPAL = new BigDecimal("1000000.00");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @BeforeAll
    static void applyMigrationsAndAccrualFunction() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/policyloan/V1__create_policyloan_schema.sql",
            "db-migrations/policyloan/V2__partition_tenant_controls.sql",
            "db-migrations/policyloan/V3__money_check_constraints.sql",
            "db-migrations/policyloan/V4__persist_reservation_id.sql",
            "db-migrations/policyloan/V5__loan_interest_accrual.sql");

        String fullFile = Files.readString(SWEEP_FILE);
        String functionOnly = fullFile.substring(0, fullFile.indexOf("-- Daily at 03:00"));
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(functionOnly);
            ensureLedgerPartitions(connection);
        }
    }

    /**
     * V1 hand-writes only the 2026-08 and 2026-09 partitions of {@code loan_transaction}, with its
     * own comment deferring the rest to pg_partman -- which is not installed in this bare
     * container. Every test here writes ledger rows dated within the last few days, so without
     * this the whole class would start failing the moment the calendar left 2026-09 ("no partition
     * of relation ... found for row"), which is a date-fragile suite rather than a real defect.
     * Creates this month and the previous one, idempotently.
     */
    private static void ensureLedgerPartitions(Connection connection) throws Exception {
        for (YearMonth month : new YearMonth[] {YearMonth.now().minusMonths(1), YearMonth.now(), YearMonth.now().plusMonths(1)}) {
            String suffix = month.toString().replace('-', '_');
            try (Statement statement = connection.createStatement()) {
                statement.execute(String.format(
                    "CREATE TABLE IF NOT EXISTS policyloan.loan_transaction_%s PARTITION OF policyloan.loan_transaction "
                    + "FOR VALUES FROM ('%s-01') TO ('%s-01')", suffix, month, month.plusMonths(1)));
            }
        }
    }

    private static UUID seedLoan(Connection connection, UUID tenantId, String status) throws Exception {
        UUID loanId = UUID.randomUUID();
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO policyloan.policy_loan (loan_id, tenant_id, policy_number, principal_amount, "
                + "principal_currency, status, originated_at) VALUES (?, ?, ?, ?, 'TZS', ?, now())")) {
            insert.setObject(1, loanId);
            insert.setObject(2, tenantId);
            insert.setString(3, "POL-" + loanId.toString().substring(0, 8));
            insert.setBigDecimal(4, PRINCIPAL);
            insert.setString(5, status);
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }
        return loanId;
    }

    private static void seedRate(Connection connection, UUID tenantId, UUID loanId, BigDecimal ratePercent) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO policyloan.loan_interest_term (tenant_id, loan_id, rate, effective_from) "
                + "VALUES (?, ?, ?, CURRENT_DATE - 30)")) {
            insert.setObject(1, tenantId);
            insert.setObject(2, loanId);
            insert.setBigDecimal(3, ratePercent);
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }
    }

    private static void seedTransaction(Connection connection, UUID tenantId, UUID loanId, String type,
                                        BigDecimal amount, int daysAgo) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO policyloan.loan_transaction (tenant_id, loan_id, transaction_type, amount, currency, "
                + "occurred_at, reference) VALUES (?, ?, ?, ?, 'TZS', now() - make_interval(days => ?), 'seed')")) {
            insert.setObject(1, tenantId);
            insert.setObject(2, loanId);
            insert.setString(3, type);
            insert.setBigDecimal(4, amount);
            insert.setInt(5, daysAgo);
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }
    }

    /** A loan that was disbursed {@code daysAgo} and has accrued nothing since -- the normal case. */
    private static UUID seedDisbursedLoan(Connection connection, UUID tenantId, int disbursedDaysAgo) throws Exception {
        UUID loanId = seedLoan(connection, tenantId, "DISBURSED");
        seedRate(connection, tenantId, loanId, RATE_PERCENT);
        seedTransaction(connection, tenantId, loanId, "DISBURSEMENT", PRINCIPAL, disbursedDaysAgo);
        return loanId;
    }

    private static void runSweep(Connection connection) throws Exception {
        try (Statement sweep = connection.createStatement()) {
            sweep.execute("SELECT policyloan.accrue_loan_interest()");
        }
    }

    private record Accrual(int count, BigDecimal total, String lastReference) {}

    private static Accrual accrualsOf(Connection connection, UUID loanId) throws Exception {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT count(*) AS n, COALESCE(sum(amount), 0) AS total, "
                + "(SELECT reference FROM policyloan.loan_transaction WHERE loan_id = ? "
                + " AND transaction_type = 'INTEREST_ACCRUAL' ORDER BY occurred_at DESC LIMIT 1) AS last_ref "
                + "FROM policyloan.loan_transaction WHERE loan_id = ? AND transaction_type = 'INTEREST_ACCRUAL'")) {
            select.setObject(1, loanId);
            select.setObject(2, loanId);
            try (ResultSet rs = select.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return new Accrual(rs.getInt("n"), rs.getBigDecimal("total"), rs.getString("last_ref"));
            }
        }
    }

    /** actual/365 simple interest over {@code days}, half-up to 2dp -- the convention the sweep documents. */
    private static BigDecimal expectedInterest(BigDecimal balance, int days) {
        return balance.multiply(RATE_PERCENT).movePointLeft(2)
            .multiply(new BigDecimal(days))
            .divide(new BigDecimal("365"), 2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * The cross-tenant assertion, and the reason this sweep is SQL at all: a Java
     * {@code @Scheduled} thread has no {@code TenantContext}, so RLS would show it zero rows on
     * every table here. Two different tenants must BOTH accrue in one pass.
     *
     * <p>Also the status negative control. Only DISBURSED and REPAYING may accrue -- money that
     * has not left the building yet (ORIGINATED) must not be charged interest, and a terminal
     * loan (SETTLED) must not keep growing. The ORIGINATED loan is seeded with a DISBURSEMENT
     * ledger row on purpose, so it would accrue if the sweep filtered on the ledger alone and
     * forgot the status predicate.
     */
    @Test
    void accruesForEveryTenantAndOnlyForDisbursedOrRepayingLoans() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            UUID disbursedA = seedDisbursedLoan(connection, tenantA, 1);
            UUID disbursedB = seedDisbursedLoan(connection, tenantB, 1);

            UUID repayingB = seedLoan(connection, tenantB, "REPAYING");
            seedRate(connection, tenantB, repayingB, RATE_PERCENT);
            seedTransaction(connection, tenantB, repayingB, "DISBURSEMENT", PRINCIPAL, 1);

            UUID originatedA = seedLoan(connection, tenantA, "ORIGINATED");
            seedRate(connection, tenantA, originatedA, RATE_PERCENT);
            seedTransaction(connection, tenantA, originatedA, "DISBURSEMENT", PRINCIPAL, 1);

            UUID settledA = seedLoan(connection, tenantA, "SETTLED");
            seedRate(connection, tenantA, settledA, RATE_PERCENT);
            seedTransaction(connection, tenantA, settledA, "DISBURSEMENT", PRINCIPAL, 1);

            // NEGATIVE CONTROL: nothing has accrued yet, which is precisely the bug this closes.
            for (UUID loanId : new UUID[] {disbursedA, disbursedB, repayingB, originatedA, settledA}) {
                assertThat(accrualsOf(connection, loanId).count()).isZero();
            }

            runSweep(connection);

            BigDecimal oneDay = expectedInterest(PRINCIPAL, 1);
            assertThat(oneDay).isEqualByComparingTo("328.77");   // 1,000,000 x 12% x 1/365

            assertThat(accrualsOf(connection, disbursedA).total())
                .as("tenant A's disbursed loan accrues")
                .isEqualByComparingTo(oneDay);
            assertThat(accrualsOf(connection, disbursedB).total())
                .as("tenant B accrues in the SAME pass -- the cross-tenant property that makes this SQL")
                .isEqualByComparingTo(oneDay);
            assertThat(accrualsOf(connection, repayingB).total())
                .as("REPAYING accrues too: partially repaid is still borrowed money")
                .isEqualByComparingTo(oneDay);

            assertThat(accrualsOf(connection, originatedA).count())
                .as("ORIGINATED has not been paid out -- charging interest would bill for money never held")
                .isZero();
            assertThat(accrualsOf(connection, settledA).count())
                .as("SETTLED is terminal and must not keep growing")
                .isZero();
        }
    }

    /**
     * Idempotence is load-bearing, not incidental: a manual run, a retried tick, or a second
     * scheduler firing must not double-charge a policyholder. The window is a day COUNT measured
     * from the last accrual, so a same-day re-run computes zero days and writes nothing.
     */
    @Test
    void reRunningOnTheSameDayChargesNothingFurther() throws Exception {
        UUID tenantId = UUID.randomUUID();

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            UUID loanId = seedDisbursedLoan(connection, tenantId, 1);

            runSweep(connection);
            Accrual afterFirst = accrualsOf(connection, loanId);
            assertThat(afterFirst.count()).isEqualTo(1);

            runSweep(connection);
            runSweep(connection);

            assertThat(accrualsOf(connection, loanId))
                .as("three sweeps on one day must charge exactly one day of interest, once")
                .isEqualTo(afterFirst);
        }
    }

    /**
     * The property a fixed "accrue one day" implementation would silently get wrong. If the job
     * does not run for several days -- container down, cron disabled, or a tick failing unnoticed
     * because {@code pg_cron_job_failed_total} has no producer on this platform -- the next
     * successful run must charge the whole gap, not forgive it. Under-accrual is a financial
     * misstatement that no test, metric or exception would otherwise surface.
     */
    @Test
    void catchesUpEveryMissedDayInOneRun() throws Exception {
        UUID tenantId = UUID.randomUUID();

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            UUID loanId = seedDisbursedLoan(connection, tenantId, 5);

            runSweep(connection);

            BigDecimal fiveDays = expectedInterest(PRINCIPAL, 5);
            assertThat(fiveDays).isEqualByComparingTo("1643.84");   // 1,000,000 x 12% x 5/365
            Accrual accrual = accrualsOf(connection, loanId);
            assertThat(accrual.count())
                .as("one catch-up entry covering the whole window, not five separate rows")
                .isEqualTo(1);
            assertThat(accrual.total()).isEqualByComparingTo(fiveDays);
            assertThat(accrual.lastReference())
                .as("the charged window is recorded so a catch-up is visibly a catch-up, not an unexplained spike")
                .isEqualTo("ACCRUAL " + LocalDate.now().minusDays(4) + ".." + LocalDate.now() + " @ 12.0000% actual/365");
        }
    }

    /**
     * Interest capitalizes: because the balance the sweep reads already includes previously
     * accrued interest, the second run charges interest on principal PLUS the first accrual. This
     * is the compounding-between-runs behavior the sweep's header documents as a placeholder
     * actuarial convention, and it is asserted rather than assumed so a future change to it is a
     * deliberate edit here, not a silent drift.
     */
    @Test
    void accruesOnTheGrownBalanceSoInterestCapitalizes() throws Exception {
        UUID tenantId = UUID.randomUUID();

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            UUID loanId = seedLoan(connection, tenantId, "DISBURSED");
            seedRate(connection, tenantId, loanId, RATE_PERCENT);
            seedTransaction(connection, tenantId, loanId, "DISBURSEMENT", PRINCIPAL, 4);
            // An accrual already booked as at 2 days ago: the sweep must charge only the 2 days
            // since, and must charge them on the LARGER balance.
            BigDecimal priorAccrual = new BigDecimal("657.53");
            seedTransaction(connection, tenantId, loanId, "INTEREST_ACCRUAL", priorAccrual, 2);

            runSweep(connection);

            BigDecimal expected = expectedInterest(PRINCIPAL.add(priorAccrual), 2);
            assertThat(accrualsOf(connection, loanId).total())
                .as("total = the pre-seeded accrual plus 2 days charged on principal + that accrual")
                .isEqualByComparingTo(priorAccrual.add(expected));
        }
    }

    /**
     * A loan repaid down to zero that is still sitting in REPAYING -- {@code recordRepayment}
     * only marks SETTLED on its own path, so this state is reachable -- must not accrue. Without
     * the balance guard the sweep would charge interest on a zero balance, and V3's
     * {@code CHECK (amount > 0)} would then abort the entire sweep for every remaining tenant.
     */
    @Test
    void neverAccruesOnAFullyRepaidBalance() throws Exception {
        UUID tenantId = UUID.randomUUID();

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            UUID repaid = seedLoan(connection, tenantId, "REPAYING");
            seedRate(connection, tenantId, repaid, RATE_PERCENT);
            seedTransaction(connection, tenantId, repaid, "DISBURSEMENT", PRINCIPAL, 3);
            seedTransaction(connection, tenantId, repaid, "REPAYMENT", PRINCIPAL, 1);

            // A healthy loan in the same pass, so this test cannot pass merely because the sweep
            // aborted or did nothing at all.
            UUID healthy = seedDisbursedLoan(connection, tenantId, 1);

            runSweep(connection);

            assertThat(accrualsOf(connection, repaid).count())
                .as("zero outstanding balance accrues nothing")
                .isZero();
            assertThat(accrualsOf(connection, healthy).count())
                .as("...and the sweep still completed for other loans, so the guard skips rather than aborts")
                .isEqualTo(1);
        }
    }

    /**
     * A loan with no DISBURSEMENT ledger entry has no defensible date to accrue from, and must be
     * skipped rather than charged from {@code created_at} -- which would bill the policyholder for
     * a period during which they held no money. Reachable in practice: M3 shipped with nothing
     * consuming {@code LoanDisbursementRequested}, so loans legitimately rest pre-disbursement.
     */
    @Test
    void skipsALoanThatWasNeverActuallyDisbursed() throws Exception {
        UUID tenantId = UUID.randomUUID();

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            UUID noLedger = seedLoan(connection, tenantId, "DISBURSED");
            seedRate(connection, tenantId, noLedger, RATE_PERCENT);

            runSweep(connection);

            assertThat(accrualsOf(connection, noLedger).count()).isZero();
        }
    }

    /**
     * A loan with no {@code loan_interest_term} row, or a zero rate, accrues nothing rather than
     * failing. Zero-rate loans are a real product possibility, and V3's
     * {@code chk_loan_interest_term_rate_non_negative} explicitly permits {@code rate = 0}.
     */
    @Test
    void aZeroOrMissingRateAccruesNothing() throws Exception {
        UUID tenantId = UUID.randomUUID();

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            UUID noRate = seedLoan(connection, tenantId, "DISBURSED");
            seedTransaction(connection, tenantId, noRate, "DISBURSEMENT", PRINCIPAL, 2);

            UUID zeroRate = seedLoan(connection, tenantId, "DISBURSED");
            seedRate(connection, tenantId, zeroRate, BigDecimal.ZERO);
            seedTransaction(connection, tenantId, zeroRate, "DISBURSEMENT", PRINCIPAL, 2);

            runSweep(connection);

            assertThat(accrualsOf(connection, noRate).count()).isZero();
            assertThat(accrualsOf(connection, zeroRate).count()).isZero();
        }
    }

    /**
     * Uses the rate effective as at today, not the newest row outright. L1's whole reason for
     * making {@code loan_interest_term} effective-dated is that a future-dated rate change can be
     * recorded ahead of time; picking it up early would re-rate the loan before it takes effect.
     */
    @Test
    void usesTheRateEffectiveTodayAndIgnoresAFutureDatedOne() throws Exception {
        UUID tenantId = UUID.randomUUID();

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            UUID loanId = seedLoan(connection, tenantId, "DISBURSED");
            seedRate(connection, tenantId, loanId, RATE_PERCENT);
            seedTransaction(connection, tenantId, loanId, "DISBURSEMENT", PRINCIPAL, 1);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO policyloan.loan_interest_term (tenant_id, loan_id, rate, effective_from) "
                    + "VALUES (?, ?, 99.0000, CURRENT_DATE + 30)")) {
                insert.setObject(1, tenantId);
                insert.setObject(2, loanId);
                assertThat(insert.executeUpdate()).isEqualTo(1);
            }

            runSweep(connection);

            assertThat(accrualsOf(connection, loanId).total())
                .as("the 99% rate takes effect in 30 days and must not be charged today")
                .isEqualByComparingTo(expectedInterest(PRINCIPAL, 1));
        }
    }

    /**
     * The cross-module handoff. The sweep cannot evaluate forced lapse itself -- that needs cash
     * value from {@code policy.policy_account}, a schema this module is documented to read only
     * through {@code PolicyApi} -- so it flags the loan and per-tenant Java does the compare.
     *
     * <p>The version bump is asserted because it is load-bearing: {@code policy_loan.version}
     * backs JPA's {@code @Version}, and without the bump an entity loaded before the sweep and
     * saved after it would pass Hibernate's optimistic-lock check and silently reset this flag to
     * NULL, dropping a pending forced-lapse review with no error anywhere.
     */
    @Test
    void flagsAnAccruedLoanForForcedLapseReviewAndBumpsTheVersion() throws Exception {
        UUID tenantId = UUID.randomUUID();

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            UUID accruing = seedDisbursedLoan(connection, tenantId, 1);
            UUID notAccruing = seedLoan(connection, tenantId, "ORIGINATED");
            seedRate(connection, tenantId, notAccruing, RATE_PERCENT);

            assertThat(reviewFlagOf(connection, accruing)).isNull();
            long versionBefore = versionOf(connection, accruing);

            runSweep(connection);

            assertThat(reviewFlagOf(connection, accruing))
                .as("an accrued loan is queued for the shortfall re-check")
                .isNotNull();
            assertThat(versionOf(connection, accruing))
                .as("version must advance, or a stale JPA write silently clears the review flag")
                .isEqualTo(versionBefore + 1);
            assertThat(reviewFlagOf(connection, notAccruing))
                .as("a loan that did not accrue has no new shortfall risk and must not be queued")
                .isNull();
        }
    }

    private static java.sql.Timestamp reviewFlagOf(Connection connection, UUID loanId) throws Exception {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT forced_lapse_review_due_at FROM policyloan.policy_loan WHERE loan_id = ?")) {
            select.setObject(1, loanId);
            try (ResultSet rs = select.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getTimestamp(1);
            }
        }
    }

    private static long versionOf(Connection connection, UUID loanId) throws Exception {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT version FROM policyloan.policy_loan WHERE loan_id = ?")) {
            select.setObject(1, loanId);
            try (ResultSet rs = select.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getLong(1);
            }
        }
    }

    /**
     * Postgres grants EXECUTE on a newly created function to PUBLIC by default -- unlike tables,
     * where a bare CREATE grants nothing -- so without an explicit REVOKE, app_role could call
     * this SECURITY DEFINER function directly despite being NOSUPERUSER NOBYPASSRLS, which is
     * exactly the request-path privilege escalation the function's own header says must never be
     * possible. That gap was open in configure-billing-sweep.sql from M4 until M7 found it.
     * app_role is not bootstrapped in this bare container (only MigrationTestSupport's
     * placeholder), so this checks PUBLIC -- the mechanism that was silently granting the access.
     */
    @Test
    void appRoleHasNoExecutePrivilegeOnTheAccrualFunction() throws Exception {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement select = connection.prepareStatement(
                 "SELECT has_function_privilege('public', 'policyloan.accrue_loan_interest()', 'EXECUTE')")) {
            try (ResultSet rs = select.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1))
                    .as("PUBLIC (and therefore app_role) must not be able to execute this SECURITY DEFINER function directly")
                    .isFalse();
            }
        }
    }

    /**
     * Executes the EXACT command string pg_cron is registered to run, parsed out of the real
     * post-migration file rather than retyped here -- so the thing under test is the thing that
     * ships. This is not a hypothetical gap: configure-billing-sweep.sql shipped in M4 scheduling
     * {@code CALL billing.sweep_billing_state()} against a FUNCTION, Postgres answers CALL on a
     * function with "is not a procedure", and that sweep failed on every 15-minute tick until M7
     * found it -- unnoticed because {@code pg_cron_job_failed_total} has no producer.
     */
    @Test
    void theScheduledCommandStringActuallyExecutes() throws Exception {
        String fullFile = Files.readString(SWEEP_FILE);
        Matcher matcher = Pattern.compile("cron\\.schedule\\([^$]*\\$\\$(.*?)\\$\\$", Pattern.DOTALL).matcher(fullFile);
        assertThat(matcher.find())
            .as("configure-loan-interest-accrual.sql must register a cron job with a $$-quoted command")
            .isTrue();
        String scheduledCommand = matcher.group(1).trim();

        // Guards against the regex silently matching something harmless if the file is restructured.
        assertThat(scheduledCommand).contains("policyloan.accrue_loan_interest()");

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            // Fails with "is not a procedure" if this is ever changed to CALL.
            statement.execute(scheduledCommand);
        }
    }
}
