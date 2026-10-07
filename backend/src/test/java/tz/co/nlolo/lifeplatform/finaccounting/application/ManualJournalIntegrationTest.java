package tz.co.nlolo.lifeplatform.finaccounting.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingValidationException;
import tz.co.nlolo.lifeplatform.finaccounting.api.ManualJournalApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.ManualJournalApi.ManualJournalView;
import tz.co.nlolo.lifeplatform.finaccounting.api.ManualJournalInput;
import tz.co.nlolo.lifeplatform.finaccounting.api.ManualJournalStateException;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IFRS 17 I4: a manual journal is prepared, checked, approved by a second person and only then posted -- MANUAL,
 * with its people, reason, reason code and documents; corrected by an approved reversal, once; an accrual reversed
 * by the platform on its date. Against the real ledger guards (app_role, RLS).
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class ManualJournalIntegrationTest {

    private static final String APP_ROLE_PASSWORD = "manual_journal_it_password";
    private static final String THIS_MONTH = YearMonth.now(ZoneId.of("Africa/Dar_es_Salaam")).toString();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "app_role");
        registry.add("spring.datasource.password", () -> APP_ROLE_PASSWORD);
    }

    @BeforeAll
    static void applyMigrationsAndBootstrapAppRole() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V9__journal_reason_codes.sql",
            "db-migrations/policyloan/V1__create_policyloan_schema.sql",
            "db-migrations/policyloan/V2__partition_tenant_controls.sql",
            "db-migrations/policyloan/V7__q4_2026_partitions.sql",
            "db-migrations/policyloan/V8__interest_month_published.sql",
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
            "db-migrations/finaccounting/V14__manual_journals.sql",
            "db-migrations/finaccounting/V15__engine_period_cycle.sql",
            "db-migrations/finaccounting/V16__expense_allocation.sql",
            "db-migrations/finaccounting/V17__year_end_close.sql",
            "db-migrations/finaccounting/V18__policy_snapshot_lives.sql");
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE app_role LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '"
                + APP_ROLE_PASSWORD + "'");
        }
    }

    @Autowired private ManualJournalApi journals;
    @Autowired private FinaccountingApi ledger;
    @Autowired private AutoReversalJob autoReversals;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    /** Payroll (guide O-01): salaries against social security payable -- both MAN accounts. */
    private static ManualJournalInput payroll(LocalDate autoReverseOn) {
        return new ManualJournalInput(THIS_MONTH, "TZS", "October payroll", "Payroll summary from HR", null, "O-01",
            autoReverseOn, List.of(
                new ManualJournalInput.Line("8110", PostingDirection.DR, new BigDecimal("4500000.00"), "Salaries",
                    "DSM", null, null, null),
                new ManualJournalInput.Line("2640", PostingDirection.CR, new BigDecimal("4500000.00"), "NSSF and PAYE",
                    "DSM", null, null, null)));
    }

    @Test
    void aJournalIsCheckedThenApprovedByASecondPersonAndOnlyThenPosted() {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(tenant);
        ManualJournalView draft = journals.create(payroll(null), "finance-one");
        assertThat(draft.status()).isEqualTo("DRAFT");
        assertThat(draft.totalDebit()).isEqualByComparingTo(draft.totalCredit());
        assertThat(ledger.listJournalEntries(null, null, org.springframework.data.domain.Pageable.unpaged()).getContent())
            .as("nothing posted while a draft").isEmpty();

        assertThatThrownBy(() -> journals.submit(draft.id(), "finance-one"))
            .isInstanceOf(FinaccountingValidationException.class)
            .hasMessageContaining("Attach the document");
        journals.attachDocument(draft.id(), "doc-payroll-oct", "finance-one");
        assertThatThrownBy(() -> journals.attachDocument(draft.id(), "doc-x", "finance-two"))
            .isInstanceOf(ManualJournalStateException.class).hasMessageContaining("who prepared it");

        assertThat(journals.submit(draft.id(), "finance-one").status()).isEqualTo("SUBMITTED");
        assertThatThrownBy(() -> journals.approve(draft.id(), "finance-one"))
            .isInstanceOf(ManualJournalStateException.class)
            .hasMessage("You prepared this journal; a second person must approve it");

        ManualJournalView approved = journals.approve(draft.id(), "finance-approver");
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.journalEntryId()).isNotNull();

        Map<String, Object> entry = jdbc.queryForMap("SELECT * FROM finaccounting.journal_entry WHERE journal_entry_id = ?",
            approved.journalEntryId());
        assertThat(entry.get("source_type")).isEqualTo("MANUAL");
        assertThat(entry.get("preparer")).isEqualTo("finance-one");
        assertThat(entry.get("approver")).isEqualTo("finance-approver");
        assertThat(entry.get("reason")).isEqualTo("Payroll summary from HR");
        assertThat(entry.get("document_refs")).isEqualTo("doc-payroll-oct");
        assertThat(entry.get("period")).isEqualTo(THIS_MONTH);
        assertThat(jdbc.queryForList("SELECT branch FROM finaccounting.gl_posting WHERE journal_entry_id = ?",
            String.class, approved.journalEntryId())).containsOnly("DSM");

        assertThatThrownBy(() -> journals.approve(draft.id(), "finance-approver"))
            .isInstanceOf(ManualJournalStateException.class);
    }

    @Test
    void aBothAccountNeedsAReasonCodeAndARejectedJournalSaysWhy() {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(tenant);
        ManualJournalInput shares = new ManualJournalInput(THIS_MONTH, "TZS", "Shares issued", "Rights issue", null, "M-01",
            null, List.of(
                new ManualJournalInput.Line("1110", PostingDirection.DR, new BigDecimal("1500.00"), null, null, null, null, null),
                new ManualJournalInput.Line("3110", PostingDirection.CR, new BigDecimal("1000.00"), null, null, null, null, null),
                new ManualJournalInput.Line("3120", PostingDirection.CR, new BigDecimal("500.00"), null, null, null, null, null)));
        ManualJournalView draft = journals.create(shares, "finance-one");
        journals.attachDocument(draft.id(), "doc-resolution", "finance-one");
        assertThatThrownBy(() -> journals.submit(draft.id(), "finance-one"))
            .isInstanceOf(FinaccountingValidationException.class)
            .hasMessageContaining("BOTH account").hasMessageContaining("reason code");

        journals.update(draft.id(), new ManualJournalInput(shares.period(), "TZS", shares.title(), shares.reason(),
            "CORRECTION", "M-01", null, shares.lines()), "finance-one");
        journals.submit(draft.id(), "finance-one");
        assertThatThrownBy(() -> journals.reject(draft.id(), " ", "finance-approver"))
            .isInstanceOf(FinaccountingValidationException.class);
        ManualJournalView rejected = journals.reject(draft.id(), "Wrong share count", "finance-approver");
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.decisionReason()).isEqualTo("Wrong share count");
        assertThat(rejected.journalEntryId()).isNull();
    }

    @Test
    void aPostedJournalIsCorrectedByAnApprovedReversalOnce() {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(tenant);
        ManualJournalView posted = approve(journals.create(payroll(null), "finance-one"));

        ManualJournalView reversal = journals.reverse(posted.id(), "finance-two");
        assertThat(reversal.status()).isEqualTo("DRAFT");
        assertThat(reversal.reversesJournalId()).isEqualTo(posted.journalEntryId());
        assertThat(reversal.documentRefs()).containsExactly("doc-1");
        assertThat(reversal.lines()).extracting(l -> l.accountCode() + " " + l.side())
            .containsExactly("8110 CR", "2640 DR");
        assertThatThrownBy(() -> journals.reverse(posted.id(), "finance-two"))
            .isInstanceOf(ManualJournalStateException.class).hasMessageContaining("already has a reversal");

        journals.submit(reversal.id(), "finance-two");
        ManualJournalView done = journals.approve(reversal.id(), "finance-approver");
        assertThat(jdbc.queryForObject("SELECT reverses_journal_id FROM finaccounting.journal_entry WHERE journal_entry_id = ?",
            UUID.class, done.journalEntryId())).isEqualTo(posted.journalEntryId());
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN direction = 'DR' THEN amount ELSE -amount END), 0)"
            + " FROM finaccounting.gl_posting WHERE account_code = '8110'", BigDecimal.class)).isZero();
    }

    @Test
    void anAccrualIsReversedByThePlatformOnItsDateWithoutASecondApproval() {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(tenant);
        LocalDate firstOfNext = YearMonth.parse(THIS_MONTH).plusMonths(1).atDay(1);
        ManualJournalView posted = approve(journals.create(payroll(firstOfNext), "finance-one"));

        autoReversals.drain(firstOfNext.minusDays(1));
        assertThat(reversals(tenant, posted.journalEntryId())).as("not before its date").isZero();
        autoReversals.drain(firstOfNext);
        autoReversals.drain(firstOfNext);
        assertThat(reversals(tenant, posted.journalEntryId())).as("once").isEqualTo(1);
        TenantContext.set(tenant);
        Map<String, Object> reversal = jdbc.queryForMap("SELECT * FROM finaccounting.journal_entry WHERE source_event = ?",
            AutoReversalJob.EVENT);
        assertThat(reversal.get("source_type")).isEqualTo("SYSTEM");
        assertThat(reversal.get("period")).isEqualTo(YearMonth.from(firstOfNext).toString());
        TenantContext.set(tenant);
        ManualJournalView after = journals.get(posted.id());
        assertThat(after.autoReversal()).isEqualTo("REVERSED");
        assertThat(after.autoReversalJournalId()).isEqualTo(reversal.get("journal_entry_id"));
    }

    /**
     * An accrual approved with a reverse-on date is the platform's to reverse. Reversing it by hand as well would post
     * the reversal twice -- the drain never looks for a manual one -- so the hand reversal is refused, before the
     * date and after it.
     */
    @Test
    void aJournalThePlatformReversesCannotAlsoBeReversedByHand() {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(tenant);
        LocalDate firstOfNext = YearMonth.parse(THIS_MONTH).plusMonths(1).atDay(1);
        ManualJournalView posted = approve(journals.create(payroll(firstOfNext), "finance-one"));

        assertThatThrownBy(() -> journals.reverse(posted.id(), "finance-two"))
            .isInstanceOf(ManualJournalStateException.class)
            .hasMessage("This journal is reversed by the platform on " + firstOfNext + "; it cannot also be reversed by hand");

        autoReversals.drain(firstOfNext);
        TenantContext.set(tenant);
        assertThatThrownBy(() -> journals.reverse(posted.id(), "finance-two"))
            .isInstanceOf(ManualJournalStateException.class);
        assertThat(reversals(tenant, posted.journalEntryId())).isEqualTo(1);
    }

    /**
     * Where the platform's reversal stands, on the journal itself: scheduled while its date is ahead, waiting while
     * its period is locked. The view reads the real calendar, so this accrual is last month's, reversing on the first
     * of this one -- a date that has come.
     */
    @Test
    void anAutoReversalWaitingOnALockedPeriodIsShownOnTheJournal() {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(tenant);
        String lastMonth = YearMonth.parse(THIS_MONTH).minusMonths(1).toString();
        LocalDate firstOfThis = YearMonth.parse(THIS_MONTH).atDay(1);
        ManualJournalInput accrual = payroll(firstOfThis);
        ManualJournalView posted = approve(journals.create(new ManualJournalInput(lastMonth, accrual.currency(),
            accrual.title(), accrual.reason(), accrual.reasonCode(), accrual.templateId(), firstOfThis, accrual.lines()),
            "finance-one"));
        assertThat(journals.create(payroll(YearMonth.parse(THIS_MONTH).plusMonths(1).atDay(1)), "finance-one"))
            .isNotNull();
        assertThat(approve(journals.list("DRAFT", THIS_MONTH, null).get(0)).autoReversal())
            .as("a date still ahead").isEqualTo("SCHEDULED");

        ledger.startClosing(lastMonth, "finance-one");
        ledger.lockPeriod(lastMonth, "finance-one");
        ledger.startClosing(THIS_MONTH, "finance-one");
        ledger.lockPeriod(THIS_MONTH, "finance-one");
        autoReversals.drain(firstOfThis);

        TenantContext.set(tenant);
        assertThat(reversals(tenant, posted.journalEntryId())).isZero();
        assertThat(journals.get(posted.id()).autoReversal()).isEqualTo("WAITING_PERIOD_LOCKED");
        assertThat(journals.list(null, null, null)).filteredOn(j -> j.id().equals(posted.id())).singleElement()
            .satisfies(j -> assertThat(j.autoReversal()).isEqualTo("WAITING_PERIOD_LOCKED"));
    }

    @Test
    void anAutoReversalIsDatedTheFirstDayOfALaterPeriod() {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(tenant);
        LocalDate midNext = YearMonth.parse(THIS_MONTH).plusMonths(1).atDay(15);
        ManualJournalView draft = journals.create(payroll(midNext), "finance-one");
        journals.attachDocument(draft.id(), "doc-1", "finance-one");
        assertThatThrownBy(() -> journals.submit(draft.id(), "finance-one"))
            .isInstanceOf(FinaccountingValidationException.class)
            .hasMessageContaining("An auto-reversal is dated the first day of a period after " + THIS_MONTH);
    }

    /** The controller asks before it stores a document, so a refused attachment leaves no orphan in the store. */
    @Test
    void onlyItsPreparerMayChangeADraftAndOnlyWhileItIsADraft() {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(tenant);
        ManualJournalView draft = journals.create(payroll(null), "finance-one");
        journals.requireEditable(draft.id(), "finance-one");
        assertThatThrownBy(() -> journals.requireEditable(draft.id(), "finance-two"))
            .isInstanceOf(ManualJournalStateException.class).hasMessageContaining("who prepared it");

        journals.attachDocument(draft.id(), "doc-1", "finance-one");
        journals.submit(draft.id(), "finance-one");
        assertThatThrownBy(() -> journals.requireEditable(draft.id(), "finance-one"))
            .isInstanceOf(ManualJournalStateException.class).hasMessageContaining("only a draft can be changed");

        assertThatThrownBy(() -> journals.withdraw(draft.id(), "finance-two"))
            .isInstanceOf(ManualJournalStateException.class);
        assertThat(journals.withdraw(draft.id(), "finance-one").status()).isEqualTo("DRAFT");
        journals.requireEditable(draft.id(), "finance-one");
    }

    @Test
    void journalsAreListedByStatusPeriodAndPreparerWithinTheirTenant() {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(tenant);
        ManualJournalView mine = journals.create(payroll(null), "finance-one");
        journals.create(payroll(null), "finance-two");
        assertThat(journals.list(null, null, "finance-one")).extracting(ManualJournalView::id).containsExactly(mine.id());
        assertThat(journals.list("DRAFT", THIS_MONTH, null)).hasSize(2);
        assertThat(journals.list("SUBMITTED", null, null)).isEmpty();

        TenantContext.set(UUID.randomUUID());
        assertThat(journals.list(null, null, null)).as("another tenant sees none of them").isEmpty();
        assertThatThrownBy(() -> journals.get(mine.id()))
            .isInstanceOf(tz.co.nlolo.lifeplatform.finaccounting.api.ManualJournalNotFoundException.class);
    }

    /** Checked again at approval: a period locked since submission refuses the journal, and nothing is posted. */
    @Test
    void aPeriodLockedAfterSubmissionRefusesTheApproval() {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(tenant);
        ManualJournalView draft = journals.create(payroll(null), "finance-one");
        journals.attachDocument(draft.id(), "doc-1", "finance-one");
        journals.submit(draft.id(), "finance-one");
        ledger.startClosing(THIS_MONTH, "finance-one");
        ledger.lockPeriod(THIS_MONTH, "finance-one");

        assertThatThrownBy(() -> journals.approve(draft.id(), "finance-approver"))
            .isInstanceOf(FinaccountingValidationException.class).hasMessageContaining("is locked");
        assertThat(journals.get(draft.id()).status()).isEqualTo("SUBMITTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM finaccounting.journal_entry WHERE source_ref = ?",
            Integer.class, draft.id().toString())).isZero();
    }

    /** A refusal by the ledger's own guard -- a period locked in the instant between check and post -- keeps its words. */
    @Test
    void aLedgerGuardsWordsAreKept() {
        RuntimeException fromTheDatabase = new RuntimeException("could not execute statement",
            new RuntimeException("ERROR: LEDGER_PERIOD_LOCKED: period 2026-08 is locked\n  Where: PL/pgSQL function"));
        assertThat(ManualJournalApiImpl.ledgerGuard(fromTheDatabase)).isEqualTo("LEDGER_PERIOD_LOCKED: period 2026-08 is locked");
        assertThat(ManualJournalApiImpl.ledgerGuard(new RuntimeException("connection reset"))).isNull();
    }

    @Test
    void aRecurringJournalIsSavedAsATemplateOnceByName() {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(tenant);
        var saved = journals.saveTemplate("Monthly payroll", "Salaries against NSSF and PAYE",
            payroll(null).lines(), null, "finance-one");
        assertThat(saved.source()).isEqualTo("SAVED");
        assertThat(saved.lines()).extracting(l -> l.accountCode() + " " + l.side() + " " + l.amount().toPlainString())
            .containsExactly("8110 DR 4500000.00", "2640 CR 4500000.00");
        assertThat(journals.templates()).filteredOn(t -> "SAVED".equals(t.source())).hasSize(1);
        assertThatThrownBy(() -> journals.saveTemplate("Monthly payroll", null, payroll(null).lines(), null, "finance-one"))
            .isInstanceOf(ManualJournalStateException.class).hasMessageContaining("already exists");

        TenantContext.set(UUID.randomUUID());
        assertThat(journals.templates()).filteredOn(t -> "SAVED".equals(t.source())).isEmpty();
    }

    @Test
    void linesComeFromAFileAndTheGuidesTemplatesAreOffered() {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(tenant);
        ManualJournalView draft = journals.create(new ManualJournalInput(THIS_MONTH, "TZS", "Opening balances",
            "Go-live", "MIGRATION", "O-11", null, List.of()), "finance-one");
        String csv = "account,side,amount,description\n8110,DR,100.00,a\n2640,CR,100.00,b\n";
        ManualJournalView loaded = journals.uploadLines(draft.id(), csv.getBytes(StandardCharsets.UTF_8), "lines.csv",
            "finance-one");
        assertThat(loaded.lines()).hasSize(2);
        assertThatThrownBy(() -> journals.uploadLines(draft.id(), "account,side,amount\nX,DR,1\n".getBytes(StandardCharsets.UTF_8),
                "bad.csv", "finance-one"))
            .isInstanceOf(FinaccountingValidationException.class).hasMessageContaining("Row 2");
        assertThat(journals.get(draft.id()).lines()).as("a bad file changes nothing").hasSize(2);

        assertThat(journals.templates()).filteredOn(t -> "GUIDE".equals(t.source())).hasSize(52)
            .filteredOn(t -> t.postedBy() == null).hasSize(39);
    }

    private ManualJournalView approve(ManualJournalView draft) {
        journals.attachDocument(draft.id(), "doc-1", "finance-one");
        journals.submit(draft.id(), "finance-one");
        return journals.approve(draft.id(), "finance-approver");
    }

    private int reversals(UUID tenant, UUID original) {
        TenantContext.set(tenant);
        Integer n = jdbc.queryForObject("SELECT count(*) FROM finaccounting.journal_entry WHERE source_event = ?"
            + " AND source_ref = ?", Integer.class, AutoReversalJob.EVENT, original.toString());
        return n == null ? 0 : n;
    }
}
