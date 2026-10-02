package tz.co.nlolo.lifeplatform.accumulation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.accumulation.api.*;
import tz.co.nlolo.lifeplatform.accumulation.application.LedgerService;
import tz.co.nlolo.lifeplatform.accumulation.infrastructure.PostingRepository;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.product.api.AccumulationPlan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ledger service against a real database: an account opens only on an ACCOUNT version, a posting
 * writes contiguous entries whose projection reaches the policy, and one source posts once -- however
 * often it arrives, and even when two deliveries race.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(AccumulationTestFixtures.class)
class LedgerServiceIntegrationTest {

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
            "db-migrations/party/V1__create_party_schema.sql",
            "db-migrations/party/V2__individual_person_record.sql",
            "db-migrations/party/V4__registered_by_agent.sql",
            "db-migrations/party/V5__registered_by_name.sql",
            "db-migrations/party/V6__client_reference.sql",
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
            "db-migrations/product/V20__deposit_rate_grid.sql",
            "db-migrations/accumulation/V1__create_accumulation_schema.sql",
            "db-migrations/benefitpayout/V1__create_benefitpayout_schema.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/underwriting/V2__agent_of_record.sql",
            "db-migrations/underwriting/V3__medical_disclosure_recorded_by.sql",
            "db-migrations/underwriting/V4__proposal_identity.sql",
            "db-migrations/underwriting/V5__explicit_decision.sql",
            "db-migrations/underwriting/V6__proposal_terms_and_beneficiaries.sql",
            "db-migrations/underwriting/V8__rating_multiplier.sql",
            "db-migrations/underwriting/V9__group_proposal.sql",
            "db-migrations/underwriting/V10__issuance_failure.sql",
            "db-migrations/underwriting/V11__member_evidence_case.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/policy/V1__create_policy_schema.sql",
            "db-migrations/policy/V2__endorsement_append_only_and_money_checks.sql",
            "db-migrations/policy/V3__premium_fields.sql",
            "db-migrations/policy/V4__underwriting_case_id.sql",
            "db-migrations/policy/V5__beneficiary_party_index.sql",
            "db-migrations/policy/V6__policy_term.sql",
            "db-migrations/policy/V7__life_assured.sql",
            "db-migrations/policy/V10__one_policy_per_underwriting_case.sql",
            "db-migrations/policy/V11__not_taken_up_status.sql",
            "db-migrations/policy/V24__issuance_record.sql",
            "db-migrations/policy/V27__expired_status.sql",
            "db-migrations/policy/V28__policies_due_to_expire.sql",
            "db-migrations/policy/V29__paid_up.sql",
            "db-migrations/policy/V30__surrender.sql",
            "db-migrations/policy/V31__free_look_status.sql",
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql");
    }

    private static final UUID TENANT = UUID.randomUUID();

    @Autowired private AccumulationTestFixtures fixtures;
    @Autowired private AccumulationApi api;
    @Autowired private LedgerService ledger;
    @Autowired private PolicyApi policyApi;
    @Autowired private PostingRepository postings;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private <T> T asTenant(java.util.function.Supplier<T> work) {
        TenantContext.set(TENANT);
        try { return work.get(); } finally { TenantContext.clear(); }
    }

    @Test
    void anAccountVersionOpensAnAccountAtIssueAndAScaleVersionDoesNot() {
        var account = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now());
        var scale = fixtures.issueSavingsPlan(TENANT, AccumulationPlan.none(), LocalDate.now());
        assertThat(asTenant(() -> api.isAccount(account.policyNumber()))).isTrue();
        // Every policy sold before this step is a scale policy, and must be left exactly as it was.
        assertThat(asTenant(() -> api.isAccount(scale.policyNumber()))).isFalse();
    }

    @Test
    void aPostingWritesContiguousEntriesAndTheProjectionFollows() {
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now());
        asTenant(() -> ledger.post(issued.policyNumber(), new LedgerService.Source("test", "test:1"), List.of(
            LedgerService.Line.of(EntryType.CONTRIBUTION, new BigDecimal("100000.00"), LocalDate.now(), "Contribution"),
            LedgerService.Line.of(EntryType.ALLOCATION_CHARGE, new BigDecimal("-5000.00"), LocalDate.now(), "5%")),
            "test", null));

        List<LedgerEntryView> entries = asTenant(() -> api.entries(issued.policyNumber()));
        assertThat(entries).extracting(LedgerEntryView::seq).containsExactly(1, 2);
        assertThat(entries).extracting(LedgerEntryView::balanceAfter)
            .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
            .containsExactly(new BigDecimal("100000.00"), new BigDecimal("95000.00"));
        // Surrender quotes and loan limits read this field. It must equal the ledger, to the cent.
        assertThat(asTenant(() -> policyApi.getCashValue(issued.policyNumber())).cashValueAmount())
            .isEqualByComparingTo("95000.00");
    }

    @Test
    void everyPostingReachesTheAuditLog() throws Exception {
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now());
        // Through a real transaction boundary, so AFTER_COMMIT fires: TransactionTemplate, not a
        // bare call from the test thread, which has no transaction to commit.
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(s ->
            asTenant(() -> ledger.post(issued.policyNumber(), new LedgerService.Source("test", "test:audited"), List.of(
                LedgerService.Line.of(EntryType.CONTRIBUTION, new BigDecimal("1000.00"), LocalDate.now(), "x")), "test", null)));
        try (var c = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var st = c.prepareStatement("SELECT count(*) FROM audit.audit_log WHERE event_type = 'accumulation.PostingRecorded' "
                 + "AND payload::text LIKE ?")) {
            st.setString(1, "%test:audited%");
            var rs = st.executeQuery();
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void theSameSourcePostsOnceHoweverOftenItArrives() {
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now());
        var source = new LedgerService.Source("test", "test:again");
        var line = List.of(LedgerService.Line.of(EntryType.CONTRIBUTION, new BigDecimal("1000.00"), LocalDate.now(), "x"));
        assertThat(asTenant(() -> ledger.post(issued.policyNumber(), source, line, "test", null))).isPresent();
        assertThat(asTenant(() -> ledger.post(issued.policyNumber(), source, line, "test", null))).isEmpty();
        assertThat(asTenant(() -> api.entries(issued.policyNumber()))).hasSize(1);
    }

    @Test
    void twoConcurrentDeliveriesOfOneSourceProduceExactlyOnePosting() throws Exception {
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now());
        var source = new LedgerService.Source("test", "test:race");
        var line = List.of(LedgerService.Line.of(EntryType.CONTRIBUTION, new BigDecimal("1000.00"), LocalDate.now(), "x"));
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<?>> both = List.of(
                pool.submit(() -> { start.await(); return asTenant(() -> attempt(issued.policyNumber(), source, line)); }),
                pool.submit(() -> { start.await(); return asTenant(() -> attempt(issued.policyNumber(), source, line)); }));
            start.countDown();
            for (var f : both) f.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        // The index, not the check, is the guarantee -- one of the two threads lost the race.
        assertThat(asTenant(() -> api.entries(issued.policyNumber()))).hasSize(1);
        assertThat(asTenant(() -> postings.findByTenantIdAndSourceTypeAndSourceRef(TENANT, "test", "test:race")))
            .isPresent();
    }

    /** A lost race throws inside its own transaction; swallowed here, as EnvelopeRunner would. */
    private Object attempt(String policyNumber, LedgerService.Source source, List<LedgerService.Line> lines) {
        try {
            return ledger.post(policyNumber, source, lines, "test", null);
        } catch (org.springframework.dao.DataIntegrityViolationException expected) {
            return null;
        }
    }

    @Test
    void aMoveThatWouldTakeTheBalanceNegativeIsRefusedAndWritesNothing() {
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now());
        assertThatThrownBy(() -> asTenant(() -> ledger.post(issued.policyNumber(), new LedgerService.Source("test", "neg"),
                List.of(LedgerService.Line.of(EntryType.POLICY_FEE, new BigDecimal("-1.00"), LocalDate.now(), "fee")),
                "test", null)))
            .isInstanceOf(AccumulationStateException.class);
        assertThat(asTenant(() -> api.entries(issued.policyNumber()))).isEmpty();
    }
}
