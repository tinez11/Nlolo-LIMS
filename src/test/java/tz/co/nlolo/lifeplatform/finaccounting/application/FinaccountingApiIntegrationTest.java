package tz.co.nlolo.lifeplatform.finaccounting.application;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.finaccounting.api.ChartOfAccountView;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.GlPostingView;
import tz.co.nlolo.lifeplatform.finaccounting.api.JournalEntryNotFoundException;
import tz.co.nlolo.lifeplatform.finaccounting.api.JournalEntryView;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.domain.JournalEntry;
import tz.co.nlolo.lifeplatform.finaccounting.domain.PostingRule;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.ChartOfAccountSeeder;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.GlPostingRepository;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.JournalEntryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/policyloan/V1__create_policyloan_schema.sql",
            "db-migrations/policyloan/V2__partition_tenant_controls.sql",
            "db-migrations/finaccounting/V1__create_finaccounting_schema.sql",
            "db-migrations/finaccounting/V2__grants_rls_chart_of_accounts_journal_entry_and_posting_columns.sql");
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

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

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
        TenantContext.set(tenantId);

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

        assertThat(journalEntryRepository.findByTenantIdOrderByPostedAtDesc(tenantId)).hasSize(1);
        assertThat(glPostingRepository.findByTenantIdAndJournalEntryIdOrderByDirectionAsc(tenantId, journalEntryId))
            .hasSize(2);
    }

    @Test
    void secondPostEntryForTheSameSourceReturnsEmptyAndWritesNothingFurtherEvenAcrossDifferingTimestamps()
            throws Exception {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        Optional<JournalEntry> first = finaccountingApiImpl.postEntry(
            balancedEntry(tenantId, "billing.PremiumCollected", "inv-dup-1", "2026-08", "POL-0002"));
        assertThat(first).isPresent();
        UUID journalEntryId = first.get().getJournalEntryId();

        TenantContext.set(tenantId);
        Optional<JournalEntry> second = finaccountingApiImpl.postEntry(
            balancedEntry(tenantId, "billing.PremiumCollected", "inv-dup-1", "2026-08", "POL-0002"));
        assertThat(second).isEmpty();

        assertThat(journalEntryRepository.findByTenantIdOrderByPostedAtDesc(tenantId)).hasSize(1);
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
        assertThat(journalEntryRepository.findByTenantIdOrderByPostedAtDesc(tenantId)).hasSize(1);
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

        assertThat(journalEntryRepository.findByTenantIdOrderByPostedAtDesc(tenantId)).isEmpty();
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

        TenantContext.set(tenantA);
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
        TenantContext.set(tenantId);
        finaccountingApiImpl.postEntry(
            balancedEntry(tenantId, "billing.PremiumInvoiceGenerated", "inv-list-1", "2026-08", "POL-LIST-A"));
        TenantContext.set(tenantId);
        finaccountingApiImpl.postEntry(
            balancedEntry(tenantId, "billing.PremiumInvoiceGenerated", "inv-list-2", "2026-09", "POL-LIST-B"));

        TenantContext.set(tenantId);
        assertThat(finaccountingApi.listJournalEntries("2026-08", null))
            .extracting(JournalEntryView::sourceRef).containsExactly("inv-list-1");
        // The falsifiable half -- a filter that ignored its argument would return both rows here too.
        TenantContext.set(tenantId);
        assertThat(finaccountingApi.listJournalEntries("2026-09", null))
            .extracting(JournalEntryView::sourceRef).containsExactly("inv-list-2");

        TenantContext.set(tenantId);
        assertThat(finaccountingApi.listJournalEntries(null, "POL-LIST-A"))
            .extracting(JournalEntryView::sourceRef).containsExactly("inv-list-1");
        TenantContext.set(tenantId);
        assertThat(finaccountingApi.listJournalEntries(null, "POL-LIST-B"))
            .extracting(JournalEntryView::sourceRef).containsExactly("inv-list-2");
    }

    @Test
    void listChartOfAccountsReturnsTheNineSeededAccountsAndSeedingTwiceDoesNotDuplicate() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        chartOfAccountSeeder.seedIfAbsent(tenantId, "system:test");
        TenantContext.set(tenantId);
        chartOfAccountSeeder.seedIfAbsent(tenantId, "system:test");

        TenantContext.set(tenantId);
        List<ChartOfAccountView> accounts = finaccountingApi.listChartOfAccounts();
        assertThat(accounts).hasSize(9);
        assertThat(accounts).extracting(ChartOfAccountView::accountCode).doesNotHaveDuplicates();
    }
}
