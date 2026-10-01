package tz.co.nlolo.lifeplatform.finaccounting.application;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.finaccounting.api.ChartOfAccountView;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountBalanceView;
import tz.co.nlolo.lifeplatform.finaccounting.api.GlPostingView;
import tz.co.nlolo.lifeplatform.finaccounting.api.TrialBalanceView;
import tz.co.nlolo.lifeplatform.finaccounting.api.JournalEntryNotFoundException;
import tz.co.nlolo.lifeplatform.finaccounting.api.JournalEntryView;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccount;
import tz.co.nlolo.lifeplatform.finaccounting.domain.ChartOfAccountBlueprint;
import tz.co.nlolo.lifeplatform.finaccounting.domain.JournalEntry;
import tz.co.nlolo.lifeplatform.finaccounting.domain.PostingRule;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.ChartOfAccountRepository;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.ChartOfAccountSeeder;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.GlPostingRepository;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.JournalEntryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Declared in {@code finaccounting.application} (not {@code finaccounting}) so it can call
 * {@link FinaccountingApiImpl#postEntry(JournalEntry)} directly -- it is deliberately
 * package-private (Task 6's per-source-module listeners are its only intended callers), and this
 * is the same access every future listener in this module will need. Mirrors
 * {@code payment.application.PaymentApiIntegrationTest}'s own documented rationale for the same
 * deviation from a bare module-name package.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class FinaccountingApiIntegrationTest {

    private static final String APP_ROLE_PASSWORD = "finaccounting_it_password";

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
        // policyloan/V1 and V2 are required even though this test never touches a loan: gl_posting
        // is PARTITION BY RANGE (created_at), Postgres does not cascade RLS/GRANT/REVOKE from a
        // partitioned parent onto its own hand-written partitions, and policyloan/V2 installs the
        // one mechanism on this platform (trg_partition_controls, a database-wide event trigger)
        // that mirrors those controls onto every partition created after it -- verified empirically
        // during Task 1's review that omitting it leaves gl_posting's two partitions with RLS
        // disabled and UPDATE/DELETE still granted to app_role.
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/product/V2__base_rate_table.sql",
            "db-migrations/product/V3__base_rate_structured_age.sql",
            "db-migrations/product/V4__rating_table_unique_band.sql",
            "db-migrations/product/V5__rating_table_age_bounds.sql",
            "db-migrations/product/V6__eligibility_bounds.sql",
            "db-migrations/product/V9__rating_table_sum_assured_bounds.sql",
            "db-migrations/product/V10__ifrs_measurement_model_on_version.sql",
            "db-migrations/product/V11__frequency_loading.sql",
            "db-migrations/product/V12__tira_filing.sql",
            "db-migrations/product/V13__benefit_calculation_method.sql",
            "db-migrations/product/V15__exclusion_periods.sql",
            "db-migrations/product/V16__base_rate_term_bands.sql",
            "db-migrations/product/V17__cash_value.sql",
            "db-migrations/product/V18__payout_schedule.sql",
            "db-migrations/product/V19__accumulation_terms.sql",
            "db-migrations/benefitpayout/V1__create_benefitpayout_schema.sql",
            "db-migrations/policyloan/V1__create_policyloan_schema.sql",
            "db-migrations/policyloan/V2__partition_tenant_controls.sql",
            "db-migrations/policyloan/V7__q4_2026_partitions.sql",
            "db-migrations/finaccounting/V1__create_finaccounting_schema.sql",
            "db-migrations/finaccounting/V2__grants_rls_chart_of_accounts_journal_entry_and_posting_columns.sql",
            "db-migrations/finaccounting/V3__account_code_foreign_key.sql",
            "db-migrations/finaccounting/V4__chart_of_account_writable_via_api.sql",
            "db-migrations/finaccounting/V5__chart_of_account_hierarchy.sql",
            "db-migrations/finaccounting/V7__q4_2026_partitions.sql");
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE app_role LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '"
                + APP_ROLE_PASSWORD + "'");
        }
    }

    @Autowired private FinaccountingApi finaccountingApi;
    @Autowired private FinaccountingApiImpl finaccountingApiImpl;
    @Autowired private JournalEntryRepository journalEntryRepository;
    @Autowired private GlPostingRepository glPostingRepository;
    @Autowired private ChartOfAccountSeeder chartOfAccountSeeder;
    @Autowired private ChartOfAccountRepository chartOfAccountRepository;
    @Autowired private org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test
    void aPaidSurrenderIsBookedOnceAgainstClaimsExpenseAndCash() {
        // Through the real listener: the event is published inside a committed transaction, so the
        // AFTER_COMMIT finaccounting listener fires exactly as it does behind policy.markSurrenderPaid.
        UUID tenantId = UUID.randomUUID();
        String surrenderRequestId = UUID.randomUUID().toString();
        java.util.Map<String, Object> payload = java.util.Map.of("surrenderRequestId", surrenderRequestId,
            "policyNumber", "POL-SURR-GL", "paidAmount", java.util.Map.of("amount", "1240000.00", "currencyCode", "TZS"));
        org.springframework.transaction.support.TransactionTemplate tx =
            new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        for (int delivery = 0; delivery < 2; delivery++) {   // a redelivery must not post twice
            tx.executeWithoutResult(s -> eventPublisher.publishEvent(
                tz.co.nlolo.lifeplatform.DomainEventEnvelope.of("policy.SurrenderPaid", tenantId, payload)));
        }

        TenantContext.set(tenantId);
        List<JournalEntryView> entries = finaccountingApi.listJournalEntries(null, "POL-SURR-GL", Pageable.unpaged()).getContent();
        assertThat(entries).extracting(JournalEntryView::sourceRef).containsExactly(surrenderRequestId);
        TenantContext.set(tenantId);
        assertThat(glPostingRepository.findByTenantIdAndAccountCodeAndPeriod(tenantId, PostingRule.CLAIMS_EXPENSE,
            java.time.YearMonth.now().toString())).hasSize(1);
    }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    /** Required since finaccounting/V3: {@code gl_posting.account_code} is now a real foreign key
     * into {@code chart_of_account (tenant_id, account_code)}, so a tenant with no chart cannot have
     * postings written for it at all. In production every listener already calls this before
     * posting; these tests call {@code postEntry} directly, below that layer, so they must seed it
     * themselves. That the FK bites here rather than being inert is the point of V3. */
    private void seedChart(UUID tenantId) {
        TenantContext.set(tenantId);
        chartOfAccountSeeder.seedIfAbsent(tenantId, "system:test");
        TenantContext.set(tenantId);
    }

    private static JournalEntry balancedEntry(UUID tenantId, String sourceEvent, String sourceRef,
                                               String period, String policyNumber) {
        JournalEntry entry = new JournalEntry(tenantId, sourceEvent, sourceRef, period, policyNumber, "system:test");
        entry.addLeg(PostingRule.CASH, PostingDirection.DR, new BigDecimal("15000.00"), "TZS");
        entry.addLeg(PostingRule.PREMIUM_RECEIVABLE, PostingDirection.CR, new BigDecimal("15000.00"), "TZS");
        return entry;
    }

    @Test
    void postEntryOnABalancedEntryWritesOneJournalEntryAndTwoGlPostingRowsReadableBackWithBothLegs() {
        UUID tenantId = UUID.randomUUID();
        seedChart(tenantId);

        Optional<JournalEntry> saved = finaccountingApiImpl.postEntry(
            balancedEntry(tenantId, "billing.PremiumInvoiceGenerated", "inv-1", "2026-08", "POL-0001"));
        assertThat(saved).isPresent();
        UUID journalEntryId = saved.get().getJournalEntryId();
        assertThat(journalEntryId).isNotNull();

        TenantContext.set(tenantId);
        JournalEntryView view = finaccountingApi.getJournalEntry(journalEntryId);
        assertThat(view.sourceEvent()).isEqualTo("billing.PremiumInvoiceGenerated");
        assertThat(view.sourceRef()).isEqualTo("inv-1");
        assertThat(view.postings()).hasSize(2);
        assertThat(view.postings()).extracting(GlPostingView::direction)
            .containsExactlyInAnyOrder(PostingDirection.DR, PostingDirection.CR);
        assertThat(view.postings()).extracting(GlPostingView::accountCode)
            .containsExactlyInAnyOrder(PostingRule.CASH, PostingRule.PREMIUM_RECEIVABLE);

        assertThat(journalEntryRepository.findByTenantIdOrderByPostedAtDesc(tenantId, Pageable.unpaged())).hasSize(1);
        assertThat(glPostingRepository.findByTenantIdAndJournalEntryIdOrderByDirectionAsc(tenantId, journalEntryId))
            .hasSize(2);
    }

    @Test
    void secondPostEntryForTheSameSourceReturnsEmptyAndWritesNothingFurtherEvenAcrossDifferingTimestamps()
            throws Exception {
        UUID tenantId = UUID.randomUUID();
        seedChart(tenantId);
        Optional<JournalEntry> first = finaccountingApiImpl.postEntry(
            balancedEntry(tenantId, "billing.PremiumCollected", "inv-dup-1", "2026-08", "POL-0002"));
        assertThat(first).isPresent();
        UUID journalEntryId = first.get().getJournalEntryId();

        TenantContext.set(tenantId);
        Optional<JournalEntry> second = finaccountingApiImpl.postEntry(
            balancedEntry(tenantId, "billing.PremiumCollected", "inv-dup-1", "2026-08", "POL-0002"));
        assertThat(second).isEmpty();

        assertThat(journalEntryRepository.findByTenantIdOrderByPostedAtDesc(tenantId, Pageable.unpaged())).hasSize(1);
        assertThat(glPostingRepository.findByTenantIdAndJournalEntryIdOrderByDirectionAsc(tenantId, journalEntryId))
            .hasSize(2);

        // The DB-level half (spec §8): bypass existsBy...'s early return entirely via raw JDBC,
        // inserting a second journal_entry row with the IDENTICAL (tenant, source_event, source_ref)
        // but at a visibly later posted_at. This must still fail on ux_journal_entry_once -- exactly
        // the case a unique index on the partitioned gl_posting could never have caught, since that
        // index would have been forced to include created_at, and a redelivered event arriving at a
        // different timestamp would then satisfy it.
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement insert = connection.prepareStatement(
                 "INSERT INTO finaccounting.journal_entry (journal_entry_id, tenant_id, source_event, "
                 + "source_ref, period, policy_number, posted_at, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, UUID.randomUUID());
            insert.setObject(2, tenantId);
            insert.setString(3, "billing.PremiumCollected");
            insert.setString(4, "inv-dup-1");
            insert.setString(5, "2026-08");
            insert.setString(6, "POL-0002");
            insert.setTimestamp(7, Timestamp.from(Instant.now().plusSeconds(3600)));
            insert.setString(8, "system:test-direct-insert");
            SQLException violation = assertThrows(SQLException.class, insert::executeUpdate);
            assertThat(violation.getMessage()).contains("ux_journal_entry_once");
        }
        assertThat(journalEntryRepository.findByTenantIdOrderByPostedAtDesc(tenantId, Pageable.unpaged())).hasSize(1);
    }

    @Test
    void postEntryOnAnUnbalancedEntryThrowsAndPersistsNothing() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        JournalEntry entry = new JournalEntry(tenantId, "claims.ClaimSettled", "claim-unbalanced",
            "2026-08", "POL-0003", "system:test");
        entry.addLeg(PostingRule.CLAIMS_EXPENSE, PostingDirection.DR, new BigDecimal("500.00"), "TZS");
        assertThat(entry.isBalanced()).isFalse();

        assertThrows(IllegalStateException.class, () -> finaccountingApiImpl.postEntry(entry));

        assertThat(journalEntryRepository.findByTenantIdOrderByPostedAtDesc(tenantId, Pageable.unpaged())).isEmpty();
        // The GL-posting half of the same guarantee: confirm no leg was written either, keyed on the
        // would-be leg's own account code and period since the throw happens before a journal_entry_id
        // is ever minted. A future reordering of the balance check relative to the posting-construction
        // loop would show up here even though it could never show up in the journal_entry-only check above.
        assertThat(glPostingRepository.findByTenantIdAndAccountCodeAndPeriod(
            tenantId, PostingRule.CLAIMS_EXPENSE, "2026-08")).isEmpty();
    }

    @Test
    void getJournalEntryThrowsForAnUnknownIdAndForACrossTenantId() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        TenantContext.set(tenantA);
        UUID unknown = UUID.randomUUID();
        assertThrows(JournalEntryNotFoundException.class, () -> finaccountingApi.getJournalEntry(unknown));

        seedChart(tenantA);
        UUID journalEntryId = finaccountingApiImpl.postEntry(
            balancedEntry(tenantA, "claims.ClaimSettled", "claim-cross-tenant", "2026-08", "POL-0004"))
            .orElseThrow().getJournalEntryId();

        // A real entry belonging to another tenant must be invisible under real RLS, not merely
        // absent -- app_role here is bootstrapped NOSUPERUSER NOBYPASSRLS.
        TenantContext.set(tenantB);
        assertThrows(JournalEntryNotFoundException.class, () -> finaccountingApi.getJournalEntry(journalEntryId));
    }

    @Test
    void listJournalEntriesFiltersByPeriodAndByPolicyNumberGenuinely() {
        UUID tenantId = UUID.randomUUID();
        seedChart(tenantId);
        finaccountingApiImpl.postEntry(
            balancedEntry(tenantId, "billing.PremiumInvoiceGenerated", "inv-list-1", "2026-08", "POL-LIST-A"));
        TenantContext.set(tenantId);
        finaccountingApiImpl.postEntry(
            balancedEntry(tenantId, "billing.PremiumInvoiceGenerated", "inv-list-2", "2026-09", "POL-LIST-B"));

        TenantContext.set(tenantId);
        assertThat(finaccountingApi.listJournalEntries("2026-08", null, Pageable.unpaged()))
            .extracting(JournalEntryView::sourceRef).containsExactly("inv-list-1");
        // The falsifiable half -- a filter that ignored its argument would return both rows here too.
        TenantContext.set(tenantId);
        assertThat(finaccountingApi.listJournalEntries("2026-09", null, Pageable.unpaged()))
            .extracting(JournalEntryView::sourceRef).containsExactly("inv-list-2");

        TenantContext.set(tenantId);
        assertThat(finaccountingApi.listJournalEntries(null, "POL-LIST-A", Pageable.unpaged()))
            .extracting(JournalEntryView::sourceRef).containsExactly("inv-list-1");
        TenantContext.set(tenantId);
        assertThat(finaccountingApi.listJournalEntries(null, "POL-LIST-B", Pageable.unpaged()))
            .extracting(JournalEntryView::sourceRef).containsExactly("inv-list-2");

        // ---- BOTH filters together, resolved in the database (finding M8). The old implementation
        // ran a period-only query and filtered policyNumber in memory afterwards; combined with
        // paging that would have filtered WITHIN a page and returned short pages. Both directions are
        // asserted, so a combined finder that silently dropped one of its two arguments fails here. ----
        TenantContext.set(tenantId);
        assertThat(finaccountingApi.listJournalEntries("2026-08", "POL-LIST-A", Pageable.unpaged()))
            .extracting(JournalEntryView::sourceRef).containsExactly("inv-list-1");
        TenantContext.set(tenantId);
        assertThat(finaccountingApi.listJournalEntries("2026-08", "POL-LIST-B", Pageable.unpaged()))
            .as("period and policyNumber must BOTH constrain -- these two rows disagree on period")
            .isEmpty();
        TenantContext.set(tenantId);
        assertThat(finaccountingApi.listJournalEntries("2026-09", "POL-LIST-B", Pageable.unpaged()))
            .extracting(JournalEntryView::sourceRef).containsExactly("inv-list-2");
    }

    /** Finding I3: with no filters this used to return every journal entry the tenant had ever
     * posted. The page size must genuinely bound the result while totalElements still reports the
     * true count, and the legs must still arrive on every item -- they are now batch-loaded for the
     * whole page in one query rather than one query per entry, and a grouping bug there would show up
     * as entries with zero legs. */
    @Test
    void listJournalEntriesHonoursItsPageSizeAndStillCarriesBothLegsOnEveryEntry() {
        UUID tenantId = UUID.randomUUID();
        seedChart(tenantId);
        for (int i = 1; i <= 5; i++) {
            TenantContext.set(tenantId);
            finaccountingApiImpl.postEntry(balancedEntry(tenantId, "billing.PremiumInvoiceGenerated",
                "inv-page-" + i, "2026-08", "POL-PAGE"));
        }

        TenantContext.set(tenantId);
        Page<JournalEntryView> firstPage = finaccountingApi.listJournalEntries(null, null, PageRequest.of(0, 2));
        assertThat(firstPage.getContent()).as("a page of 2 must contain 2 entries, not all 5").hasSize(2);
        assertThat(firstPage.getTotalElements()).as("totalElements must still report every match").isEqualTo(5);
        assertThat(firstPage.getContent()).allSatisfy(view ->
            assertThat(view.postings()).as("every entry on a batch-loaded page keeps both of its legs").hasSize(2));

        TenantContext.set(tenantId);
        Page<JournalEntryView> lastPage = finaccountingApi.listJournalEntries(null, null, PageRequest.of(2, 2));
        assertThat(lastPage.getContent()).as("the final page holds the 5th entry alone").hasSize(1);
        assertThat(lastPage.getContent().get(0).postings()).hasSize(2);
    }

    @Test
    void listChartOfAccountsReturnsTheSeededChartAndSeedingTwiceDoesNotDuplicate() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        chartOfAccountSeeder.seedIfAbsent(tenantId, "system:test");
        TenantContext.set(tenantId);
        chartOfAccountSeeder.seedIfAbsent(tenantId, "system:test");

        TenantContext.set(tenantId);
        List<ChartOfAccountView> accounts = finaccountingApi.listChartOfAccounts();
        assertThat(accounts).hasSize(ChartOfAccountBlueprint.accounts().size());
        assertThat(accounts).extracting(ChartOfAccountView::accountCode).doesNotHaveDuplicates();
    }

    // ============================================================================================
    // The chart decides where a posting may land.
    //
    // fk_gl_posting_account_code already guarantees the account EXISTS; it cannot know whether the
    // account is a non-posting header or a retired one. Without the guard these four tests cover,
    // posting_allowed and status would be decoration.
    // ============================================================================================

    /** One balanced DR/CR pair for 100.00, with a source_ref unique per call so postEntry's
     *  idempotency early-return can never mask the assertion under test. */
    private static JournalEntry balancedEntryAgainst(UUID tenantId, String debitCode,
                                                      String creditCode, String currency) {
        JournalEntry entry = new JournalEntry(tenantId, "test.PostingGuard",
            UUID.randomUUID().toString(), "2026-08", null, "system:test");
        entry.addLeg(debitCode, PostingDirection.DR, new BigDecimal("100.00"), currency);
        entry.addLeg(creditCode, PostingDirection.CR, new BigDecimal("100.00"), currency);
        return entry;
    }

    @Test
    void refusesALegTargetingAHeaderAccount() {
        UUID tenantId = UUID.randomUUID();
        seedChart(tenantId);

        // 1000 Assets has children, so it is a header and never posts.
        JournalEntry entry = balancedEntryAgainst(tenantId, "1000", PostingRule.CASH, "TZS");
        assertThatThrownBy(() -> finaccountingApiImpl.postEntry(entry))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("1000")
            .hasMessageContaining("does not accept postings");
    }

    @Test
    void refusesALegTargetingAnInactiveAccount() {
        UUID tenantId = UUID.randomUUID();
        seedChart(tenantId);

        ChartOfAccount investments = chartOfAccountRepository
            .findByTenantIdAndAccountCode(tenantId, "1300").orElseThrow();
        investments.deactivate("system:test");
        chartOfAccountRepository.saveAndFlush(investments);

        TenantContext.set(tenantId);
        JournalEntry entry = balancedEntryAgainst(tenantId, "1300", PostingRule.CASH, "TZS");
        assertThatThrownBy(() -> finaccountingApiImpl.postEntry(entry))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("1300")
            .hasMessageContaining("does not accept postings");
    }

    @Test
    void refusesALegWhoseCurrencyDisagreesWithItsAccount() {
        UUID tenantId = UUID.randomUUID();
        seedChart(tenantId);

        // Both legs are USD, so the entry is internally consistent and balanced -- it is the
        // ACCOUNT, seeded in TZS, that disagrees.
        JournalEntry entry = balancedEntryAgainst(
            tenantId, PostingRule.PREMIUM_RECEIVABLE, PostingRule.UNEARNED_PREMIUM, "USD");
        assertThatThrownBy(() -> finaccountingApiImpl.postEntry(entry))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("USD")
            .hasMessageContaining("TZS");
    }

    @Test
    void stillPostsABalancedEntryBetweenTwoPostableAccounts() {
        UUID tenantId = UUID.randomUUID();
        seedChart(tenantId);

        JournalEntry entry = balancedEntryAgainst(
            tenantId, PostingRule.PREMIUM_RECEIVABLE, PostingRule.UNEARNED_PREMIUM, "TZS");
        assertThat(finaccountingApiImpl.postEntry(entry)).isPresent();
    }

    // ---- Balances: the aggregation this module never had -------------------------------------

    private AccountBalanceView accountIn(TrialBalanceView balance, String accountCode) {
        return balance.accounts().stream()
            .filter(a -> a.accountCode().equals(accountCode)).findFirst()
            .orElseThrow(() -> new AssertionError("no account " + accountCode + " in the trial balance"));
    }

    /**
     * Nothing on this platform summed a posting before this: a ledger of thousands of balanced
     * entries could not state the balance of one account, which is what a general ledger is for.
     */
    @Test
    void trialBalanceStatesEachPostedAccountsOwnDebitsAndCredits() {
        UUID tenantId = UUID.randomUUID();
        seedChart(tenantId);
        finaccountingApiImpl.postEntry(
            balancedEntry(tenantId, "billing.PremiumInvoiceGenerated", "inv-1", "2026-08", "POL-0001"));
        TenantContext.set(tenantId);

        TrialBalanceView balance = finaccountingApi.trialBalance(null);

        assertThat(accountIn(balance, PostingRule.CASH).ownDebit()).isEqualByComparingTo("15000.00");
        assertThat(accountIn(balance, PostingRule.CASH).ownCredit()).isEqualByComparingTo("0");
        assertThat(accountIn(balance, PostingRule.PREMIUM_RECEIVABLE).ownCredit())
            .isEqualByComparingTo("15000.00");
    }

    /**
     * THE ROLL-UP, which is the whole reason the chart is a hierarchy. A parent takes no postings
     * of its own -- `postingAllowed` is false for it and the ledger refuses a leg naming it -- so
     * without rolling up, every summary account would report zero against a branch holding
     * millions.
     */
    @Test
    void aParentAccountRollsUpItsDescendantsWhileReportingNoPostingsOfItsOwn() {
        UUID tenantId = UUID.randomUUID();
        seedChart(tenantId);
        finaccountingApiImpl.postEntry(
            balancedEntry(tenantId, "billing.PremiumInvoiceGenerated", "inv-1", "2026-08", "POL-0001"));
        TenantContext.set(tenantId);

        TrialBalanceView balance = finaccountingApi.trialBalance(null);
        AccountBalanceView leaf = accountIn(balance, PostingRule.CASH);
        AccountBalanceView root = accountIn(balance, "1000");

        // CASH is 1120, under 1100 "Cash and Cash Equivalents", under 1000 "Assets" -- so this is
        // a TWO-level walk, and asserting the intermediate is what tells a real recursive roll-up
        // apart from one that only sums direct children.
        AccountBalanceView intermediate = accountIn(balance, "1100");

        // The leaf took the posting...
        assertThat(leaf.ownDebit()).isEqualByComparingTo("15000.00");
        // ...neither account above it took any of its own...
        assertThat(intermediate.ownDebit()).isEqualByComparingTo("0");
        assertThat(root.ownDebit()).isEqualByComparingTo("0");
        // ...and both still report it, because both are above it in the chart.
        assertThat(intermediate.debit()).isEqualByComparingTo("15000.00");
        assertThat(root.debit()).isEqualByComparingTo("15000.00");
        assertThat(root.postingAllowed()).isFalse();

        // A sibling branch must NOT absorb it -- otherwise "rolls up" would just mean "sums
        // everything".
        assertThat(accountIn(balance, "2000").debit()).isEqualByComparingTo("0");
    }

    /**
     * Signed in the account's OWN normal direction, so a positive balance always means "normal"
     * and a negative one is a real anomaly rather than an artefact of which way the subtraction
     * ran. CASH is an asset (DR-normal); PREMIUM_RECEIVABLE was credited here.
     */
    @Test
    void balanceIsNettedInTheAccountsOwnNormalDirection() {
        UUID tenantId = UUID.randomUUID();
        seedChart(tenantId);
        finaccountingApiImpl.postEntry(
            balancedEntry(tenantId, "billing.PremiumInvoiceGenerated", "inv-1", "2026-08", "POL-0001"));
        TenantContext.set(tenantId);

        TrialBalanceView balance = finaccountingApi.trialBalance(null);
        assertThat(accountIn(balance, PostingRule.CASH).balance())
            .as("a debit on a debit-normal account is a positive balance")
            .isEqualByComparingTo("15000.00");
    }

    /**
     * THE TRAP THIS PINS. Totals sum each account's OWN postings, never the rolled ones: adding
     * rolled figures counts every posting once for its account and again for every ancestor
     * above it, so a perfectly sound ledger reports a wild imbalance. A negative control is the
     * only way to show the totals are not merely "some equal pair of numbers": the entry below
     * is balanced, so DR must equal CR, AND each must equal the one posting's amount rather than
     * a multiple of it.
     */
    @Test
    void trialBalanceTotalsSumOwnPostingsSoRollUpDoesNotDoubleCount() {
        UUID tenantId = UUID.randomUUID();
        seedChart(tenantId);
        finaccountingApiImpl.postEntry(
            balancedEntry(tenantId, "billing.PremiumInvoiceGenerated", "inv-1", "2026-08", "POL-0001"));
        TenantContext.set(tenantId);

        TrialBalanceView balance = finaccountingApi.trialBalance(null);

        assertThat(balance.totalDebit()).isEqualByComparingTo("15000.00");
        assertThat(balance.totalCredit()).isEqualByComparingTo("15000.00");
        assertThat(balance.balanced()).isTrue();
    }

    /** A period filters the aggregation; a period with no postings is an empty, balanced ledger
     *  rather than an error or a null. */
    @Test
    void trialBalanceIsScopedToThePeriodAndEchoesItBack() {
        UUID tenantId = UUID.randomUUID();
        seedChart(tenantId);
        finaccountingApiImpl.postEntry(
            balancedEntry(tenantId, "billing.PremiumInvoiceGenerated", "inv-1", "2026-08", "POL-0001"));
        TenantContext.set(tenantId);

        TrialBalanceView august = finaccountingApi.trialBalance("2026-08");
        assertThat(august.period()).isEqualTo("2026-08");
        assertThat(august.totalDebit()).isEqualByComparingTo("15000.00");

        TrialBalanceView september = finaccountingApi.trialBalance("2026-09");
        assertThat(september.totalDebit()).isEqualByComparingTo("0");
        assertThat(september.balanced()).as("nothing posted is still in balance").isTrue();
        assertThat(accountIn(september, PostingRule.CASH).ownDebit()).isEqualByComparingTo("0");
    }

    // ---- The account filter that lets a balance be opened up ---------------------------------

    /**
     * Every posting already carried an account code, so "what made up this balance" was in the
     * data and unanswerable through the API -- which is why the chart of accounts and the GL
     * postings list could not reach each other.
     */
    @Test
    void journalEntriesCanBeFilteredToOneAccount() {
        UUID tenantId = UUID.randomUUID();
        seedChart(tenantId);
        // Touches CASH and PREMIUM_RECEIVABLE.
        finaccountingApiImpl.postEntry(
            balancedEntry(tenantId, "billing.PremiumInvoiceGenerated", "inv-1", "2026-08", "POL-0001"));
        // Touches PREMIUM_RECEIVABLE and UNEARNED_PREMIUM -- so CASH must NOT match it.
        finaccountingApiImpl.postEntry(
            balancedEntryAgainst(tenantId, PostingRule.PREMIUM_RECEIVABLE, PostingRule.UNEARNED_PREMIUM, "TZS"));
        TenantContext.set(tenantId);

        assertThat(finaccountingApi.listJournalEntries(null, null, PostingRule.CASH, Pageable.unpaged()))
            .as("only the entry with a CASH leg")
            .hasSize(1);
        assertThat(finaccountingApi.listJournalEntries(null, null, PostingRule.PREMIUM_RECEIVABLE, Pageable.unpaged()))
            .as("both entries touch premium receivable")
            .hasSize(2);
        // Negative control: without the filter the query is genuinely wider, so the numbers above
        // are a filter doing something rather than a short list.
        assertThat(finaccountingApi.listJournalEntries(null, null, null, Pageable.unpaged())).hasSize(2);
    }

    /** An entry is returned ONCE however many of its legs hit the account. A join rather than an
     *  EXISTS would return a two-legged entry twice, and paging duplicates is how a page silently
     *  goes short. */
    @Test
    void anEntryWithBothLegsOnOneAccountIsReturnedOnce() {
        UUID tenantId = UUID.randomUUID();
        seedChart(tenantId);
        JournalEntry bothLegsSameAccount = new JournalEntry(tenantId, "test.SameAccountBothLegs",
            "same-1", "2026-08", "POL-0001", "system:test");
        bothLegsSameAccount.addLeg(PostingRule.CASH, PostingDirection.DR, new BigDecimal("100.00"), "TZS");
        bothLegsSameAccount.addLeg(PostingRule.CASH, PostingDirection.CR, new BigDecimal("100.00"), "TZS");
        finaccountingApiImpl.postEntry(bothLegsSameAccount);
        TenantContext.set(tenantId);

        assertThat(finaccountingApi.listJournalEntries(null, null, PostingRule.CASH, Pageable.unpaged()))
            .hasSize(1);
    }
}
