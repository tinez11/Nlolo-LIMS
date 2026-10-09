package tz.co.nlolo.lifeplatform.finaccounting;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.billing.api.BillingApi;
import tz.co.nlolo.lifeplatform.billing.api.InvoiceView;
import tz.co.nlolo.lifeplatform.finaccounting.api.JournalSource;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.domain.GlPosting;
import tz.co.nlolo.lifeplatform.finaccounting.domain.JournalEntry;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.GlPostingRepository;
import tz.co.nlolo.lifeplatform.finaccounting.infrastructure.JournalEntryRepository;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.product.api.BenefitType;
import tz.co.nlolo.lifeplatform.product.api.FactorType;
import tz.co.nlolo.lifeplatform.product.api.IfrsMeasurementModel;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.ProductCategory;
import tz.co.nlolo.lifeplatform.product.api.ProductSnapshotView;
import tz.co.nlolo.lifeplatform.product.api.ProductSummaryView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;
import tz.co.nlolo.lifeplatform.product.api.BenefitCalculationMethod;

/**
 * Task 6, Step 4 -- the highest-value test in the milestone: it proves the premium accrual pair
 * against the REAL billing chain rather than hand-published payloads. {@code
 * PolicyApi.issuePolicy} (real API) -> {@code policy.PolicyIssued} (real event) -> {@code
 * billing.application.PolicyEventListener} generates a real invoice, publishing a real {@code
 * billing.PremiumInvoiceGenerated} -> {@code finaccounting.application.BillingEventListener}
 * posts DR {@code 1200}/CR {@code 2200}. Then a real {@code BillingApi.applyConfirmedPayment}
 * collects that invoice in full, publishing a real {@code billing.PremiumCollected} -> the same
 * listener posts DR {@code 1000}/CR {@code 1200}.
 *
 * <p>Issued with an ANNUALLY premium frequency deliberately: {@code
 * BillingApiImpl.generateInvoicesAhead}'s 12-month horizon then produces EXACTLY ONE invoice
 * (first due date == horizon), keeping the accrual pair traceable to one, unambiguous invoice
 * rather than a batch of twelve.
 *
 * <p>Runs against real Postgres as {@code app_role} (NOSUPERUSER NOBYPASSRLS), mirroring {@code
 * ClaimSettlementEndToEndTest}/{@code CessionEndToEndTest}'s bootstrap and fixture pattern.
 * {@code policyloan/V1}/{@code V2} are required for the same reason {@code
 * FinaccountingApiIntegrationTest} needs them: {@code gl_posting}'s two hand-written partitions
 * only inherit RLS/append-only privileges through {@code policyloan/V2}'s cross-module event
 * trigger.
 *
 * <p>{@code @TransactionalEventListener(phase = AFTER_COMMIT)} chains are synchronous, same
 * thread: each producer's own {@code @Transactional} commits when its call returns, firing the
 * next listener in the chain before control comes back to this test. No await/sleep anywhere here.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(PremiumPostingEndToEndTest.EventRecorderConfiguration.class)
class PremiumPostingEndToEndTest {

    private static final String APP_ROLE_PASSWORD = "premium_posting_e2e_password";
    private static final String CURRENCY = "TZS";

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
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/refdata/V3__seed_billing_parameters.sql",
            "db-migrations/refdata/V8__ifrs17_branches_and_channels.sql",
            "db-migrations/refdata/V9__journal_reason_codes.sql",
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
            "db-migrations/product/V21__bonus_terms.sql",
            "db-migrations/product/V27__ifrs17_classification.sql",
            "db-migrations/product/V28__survival_investment_component.sql",
            "db-migrations/product/V29__funeral_group_rate.sql",
            "db-migrations/product/V30__funeral_group_rate_period.sql",
            "db-migrations/product/V31__online_listing.sql",
            "db-migrations/product/V32__account_charges.sql",
            "db-migrations/benefitpayout/V1__create_benefitpayout_schema.sql",
            "db-migrations/benefitpayout/V2__annuity_streams.sql",
            "db-migrations/benefitpayout/V3__withholding.sql",
            "db-migrations/benefitpayout/V4__withholding_rule_end.sql",
            "db-migrations/accumulation/V1__create_accumulation_schema.sql",
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
            "db-migrations/underwriting/V18__sale_channel_and_branch.sql",
            "db-migrations/underwriting/V19__group_funeral_proposal.sql",
            "db-migrations/underwriting/V20__sale_lock_backfill.sql",
            "db-migrations/underwriting/V21__case_account_charges.sql",
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
            "db-migrations/policy/V37__sale_classification.sql",
            "db-migrations/policy/V38__group_funeral_scheme.sql",
            "db-migrations/policy/V40__commencement_never_null.sql",
            "db-migrations/policy/V41__policy_account_charges.sql",
            "db-migrations/billing/V1__create_billing_schema.sql",
            "db-migrations/billing/V2__grants_rls_money_checks_and_notification_columns.sql",
            "db-migrations/billing/V3__amount_paid.sql",
            "db-migrations/billing/V5__single_premium_invoice.sql",
            "db-migrations/billing/V6__premium_credit.sql",
            "db-migrations/billing/V7__policy_inception_invoice.sql",
            "db-migrations/billing/V8__schedule_premium_paying_until.sql",
            "db-migrations/billing/V10__premium_receipt.sql",
            "db-migrations/billing/V11__premium_in_advance.sql",
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

    /** Records every published domain event so "exactly one posting, and none on redelivery" is
     * assertable directly rather than inferred from state -- same pattern as
     * {@code CessionEndToEndTest}/{@code CommissionPayoutEndToEndTest}. */
    @TestConfiguration
    static class EventRecorderConfiguration {
        @Bean
        EventRecorder premiumPostingTestEventRecorder() { return new EventRecorder(); }
    }

    static class EventRecorder {
        private final List<DomainEventEnvelope<?>> received = new CopyOnWriteArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void record(DomainEventEnvelope<?> envelope) { received.add(envelope); }

        void clear() { received.clear(); }

        List<DomainEventEnvelope<?>> ofType(String eventType) {
            return received.stream().filter(e -> eventType.equals(e.eventType())).toList();
        }
    }

    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private PolicyApi policyApi;
    @Autowired private BillingApi billingApi;
    @Autowired private JournalEntryRepository journalEntryRepository;
    @Autowired private GlPostingRepository glPostingRepository;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EventRecorder eventRecorder;

    private TransactionTemplate transactionTemplate;

    @AfterEach
    void resetAfterEach() { TenantContext.clear(); }

    private TransactionTemplate transactionTemplate() {
        if (transactionTemplate == null) {
            transactionTemplate = new TransactionTemplate(transactionManager);
        }
        return transactionTemplate;
    }

    private record Fixture(UUID applicantId, UUID productId, UUID productVersionId) {}

    /** Mirrors ClaimSettlementEndToEndTest.buildFixture/CessionEndToEndTest.buildFixture. */
    private Fixture buildFixture(UUID tenantId, String productCode) {
        TenantContext.set(tenantId);
        PartyView applicant = partyApi.registerIndividual("Premium Posting E2E Applicant " + productCode,
            LocalDate.of(1985, 3, 1), "+25571800" + String.format("%04d", Math.abs(productCode.hashCode() % 10000)),
            null, "test-agent");
        ProductSummaryView product = productApi.createProduct(productCode, "Premium Posting E2E Product",
            ProductCategory.TERM_LIFE, CURRENCY, "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        return new Fixture(applicant.partyId(), product.productId(), snapshot.productVersionId());
    }

    /** ANNUALLY, deliberately -- see class javadoc: this is the one frequency for which the
     * billing schedule's 12-month look-ahead horizon produces exactly one invoice. */
    private String issueAnnualPolicy(UUID tenantId, Fixture fixture, BigDecimal sumAssured, BigDecimal premium) {
        TenantContext.set(tenantId);
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(fixture.applicantId(), fixture.productId(),
            fixture.productVersionId(), sumAssured, CURRENCY, premium, CURRENCY, "ANNUALLY", null, List.of(),
            "Premium posting E2E test");
        String issuedPolicyNumber = policyApi.issuePolicy(null, request, "test-staff").policyNumber();
        // Cover starts with the first premium. This fixture needs a policy on risk.
        policyApi.activateOnFirstPremium(issuedPolicyNumber);
        return issuedPolicyNumber;
    }

    @Test
    void issuingAPolicyThenCollectingItsInvoiceProvesTheAccrualPairEndToEnd() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "PREMIUM-E2E-1");
        BigDecimal premium = new BigDecimal("120000.00");
        eventRecorder.clear();

        // ---- Act 1: issue the policy for real. billing generates exactly one invoice and
        // publishes billing.PremiumInvoiceGenerated; finaccounting posts DR 1200 / CR 2200. ----
        String policyNumber = issueAnnualPolicy(tenantId, fixture, new BigDecimal("2000000"), premium);

        TenantContext.set(tenantId);
        InvoiceView invoice = billingApi.getNextDueInvoice(policyNumber);
        assertThat(invoice).as("billing must have generated exactly one invoice for an ANNUALLY policy "
            + "within its 12-month look-ahead horizon").isNotNull();
        assertThat(invoice.amount()).isEqualByComparingTo(premium);

        // ---- Assertion 1: the invoice-generated journal entry, DR 1200 / CR 2200. ----
        TenantContext.set(tenantId);
        List<JournalEntry> generatedEntries = journalEntryRepository.findByTenantIdOrderByPostedAtDesc(tenantId, Pageable.unpaged()).stream()
            .filter(e -> "billing.PremiumInvoiceGenerated".equals(e.getSourceEvent()))
            .toList();
        assertThat(generatedEntries).hasSize(1);
        JournalEntry generatedEntry = generatedEntries.get(0);
        assertThat(generatedEntry.getSourceRef()).isEqualTo(invoice.invoiceId().toString());
        assertThat(generatedEntry.getPolicyNumber()).isEqualTo(policyNumber);

        TenantContext.set(tenantId);
        List<GlPosting> generatedLegs = glPostingRepository.findByTenantIdAndJournalEntryIdOrderByDirectionAsc(
            tenantId, generatedEntry.getJournalEntryId());
        assertThat(generatedLegs).as("must have exactly two balanced legs").hasSize(2);
        assertThat(generatedLegs).extracting(GlPosting::getAccountCode)
            .containsExactlyInAnyOrder("2122", "2121");
        assertThat(legFor(generatedLegs, "2122").getDirection()).isEqualTo(PostingDirection.DR);
        assertThat(legFor(generatedLegs, "2121").getDirection()).isEqualTo(PostingDirection.CR);
        assertThat(legFor(generatedLegs, "2122").getAmount()).isEqualByComparingTo(premium);

        // ---- Act 2: collect the invoice in full for real. finaccounting posts DR 1000 / CR 1200. ----
        TenantContext.set(tenantId);
        billingApi.applyConfirmedPayment(invoice.invoiceId(), premium, CURRENCY, "test-payment-ref-1");

        // ---- Assertion 2: the collected journal entry, DR 1000 / CR 1200. ----
        TenantContext.set(tenantId);
        List<JournalEntry> collectedEntries = journalEntryRepository.findByTenantIdOrderByPostedAtDesc(tenantId, Pageable.unpaged()).stream()
            .filter(e -> "billing.PremiumCollected".equals(e.getSourceEvent()))
            .toList();
        assertThat(collectedEntries).hasSize(1);
        JournalEntry collectedEntry = collectedEntries.get(0);
        assertThat(collectedEntry.getSourceRef()).isEqualTo(invoice.invoiceId().toString());

        TenantContext.set(tenantId);
        List<GlPosting> collectedLegs = glPostingRepository.findByTenantIdAndJournalEntryIdOrderByDirectionAsc(
            tenantId, collectedEntry.getJournalEntryId());
        assertThat(collectedLegs).as("must have exactly two balanced legs").hasSize(2);
        assertThat(collectedLegs).extracting(GlPosting::getAccountCode)
            .containsExactlyInAnyOrder("1140", "2122");
        assertThat(legFor(collectedLegs, "1140").getDirection()).isEqualTo(PostingDirection.DR);
        assertThat(legFor(collectedLegs, "2122").getDirection()).isEqualTo(PostingDirection.CR);
        assertThat(legFor(collectedLegs, "2122").getAmount()).isEqualByComparingTo(premium);

        // ---- Assertion 3: 1200 Premium Receivable nets to zero across the pair, for THIS invoice. ----
        BigDecimal netReceivable = BigDecimal.ZERO;
        for (GlPosting leg : generatedLegs) {
            if ("2122".equals(leg.getAccountCode())) {
                netReceivable = leg.getDirection() == PostingDirection.DR
                    ? netReceivable.add(leg.getAmount()) : netReceivable.subtract(leg.getAmount());
            }
        }
        for (GlPosting leg : collectedLegs) {
            if ("2122".equals(leg.getAccountCode())) {
                netReceivable = leg.getDirection() == PostingDirection.DR
                    ? netReceivable.add(leg.getAmount()) : netReceivable.subtract(leg.getAmount());
            }
        }
        assertThat(netReceivable).as("1200 Premium Receivable must net to zero across the accrual pair")
            .isEqualByComparingTo(BigDecimal.ZERO);

        // ---- Assertion 4: no gl_posting row anywhere has a 4xxx (income) account code. ----
        TenantContext.set(tenantId);
        List<JournalEntry> allEntries = journalEntryRepository.findByTenantIdOrderByPostedAtDesc(tenantId, Pageable.unpaged()).getContent();
        for (JournalEntry entry : allEntries) {
            TenantContext.set(tenantId);
            List<GlPosting> legs = glPostingRepository.findByTenantIdAndJournalEntryIdOrderByDirectionAsc(
                tenantId, entry.getJournalEntryId());
            assertThat(legs).as("M9 recognises no income -- no leg of %s may post to a 4xxx account",
                entry.getSourceEvent()).noneMatch(l -> l.getAccountCode().startsWith("4"));
        }

        // ---- Assertion 5: a redelivered billing.PremiumCollected is a genuine no-op -- no second
        // entry, no second posting pair, and no second finaccounting.GlPostingRecorded. ----
        eventRecorder.clear();
        Map<String, Object> redeliveredPayload = Map.of(
            "invoiceId", invoice.invoiceId(),
            "policyNumber", policyNumber,
            "amount", Map.of("amount", premium.toPlainString(), "currencyCode", CURRENCY),
            "collectedAt", Instant.now().toString());
        var envelope = DomainEventEnvelope.of("billing.PremiumCollected", tenantId, redeliveredPayload);
        TenantContext.set(tenantId);
        transactionTemplate().executeWithoutResult(status -> eventPublisher.publishEvent(envelope));

        TenantContext.set(tenantId);
        assertThat(journalEntryRepository.findByTenantIdOrderByPostedAtDesc(tenantId, Pageable.unpaged()).stream()
                .filter(e -> "billing.PremiumCollected".equals(e.getSourceEvent())).toList())
            .as("a redelivered PremiumCollected must not double-post").hasSize(1);
        TenantContext.set(tenantId);
        assertThat(glPostingRepository.findByTenantIdAndJournalEntryIdOrderByDirectionAsc(
                tenantId, collectedEntry.getJournalEntryId()))
            .as("a redelivered PremiumCollected must not add a second posting pair").hasSize(2);
        assertThat(eventRecorder.ofType("finaccounting.GlPostingRecorded"))
            .as("a redelivered PremiumCollected must not publish a second GlPostingRecorded -- an "
                + "idempotent write that still re-announces itself would mislead every downstream consumer")
            .isEmpty();

        // ---- Assertion 6: the journal records what wrote it and the accounting policy register
        // version it was posted under (IFRS 17 I1). The old csm/lrc/lic ledgers this assertion used
        // to prove empty are gone (finaccounting V10): IFRS 17 measurement reaches the ledger as
        // engine-run journals, not as rows in tables of its own. ----
        assertThat(collectedEntry.getSourceType()).isEqualTo(JournalSource.EVENT);
        assertThat(collectedEntry.getPolicyRegisterVersion()).isPositive();
        assertThat(countAllRows("pg_class WHERE relname IN ('csm_ledger', 'lrc_ledger', 'lic_ledger')"))
            .as("finaccounting V10 drops the pre-IFRS-17 measurement ledgers").isZero();
    }

    private static GlPosting legFor(List<GlPosting> legs, String accountCode) {
        return legs.stream().filter(l -> accountCode.equals(l.getAccountCode())).findFirst()
            .orElseThrow(() -> new AssertionError("No leg found for account " + accountCode));
    }

    private static long countAllRows(String qualifiedTable) {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT count(*) FROM " + qualifiedTable)) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
