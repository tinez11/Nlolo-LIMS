package tz.co.nlolo.lifeplatform.billing;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.audit.domain.AuditLogEntry;
import tz.co.nlolo.lifeplatform.audit.infrastructure.AuditLogRepository;
import tz.co.nlolo.lifeplatform.billing.api.BillingApi;
import tz.co.nlolo.lifeplatform.billing.api.FieldReceiptNotFoundException;
import tz.co.nlolo.lifeplatform.billing.api.FieldReceiptView;
import tz.co.nlolo.lifeplatform.billing.api.InvoiceView;
import tz.co.nlolo.lifeplatform.billing.application.ArrearsNotificationSweep;
import tz.co.nlolo.lifeplatform.billing.application.BillingApiImpl;
import tz.co.nlolo.lifeplatform.billing.domain.BillingSchedule;
import tz.co.nlolo.lifeplatform.billing.domain.FieldReceipt;
import tz.co.nlolo.lifeplatform.billing.domain.PremiumInvoice;
import tz.co.nlolo.lifeplatform.billing.infrastructure.BillingScheduleRepository;
import tz.co.nlolo.lifeplatform.billing.infrastructure.FieldReceiptRepository;
import tz.co.nlolo.lifeplatform.billing.infrastructure.PremiumInvoiceRepository;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;

// EventRecorderConfiguration must be @Import-ed explicitly: a nested @TestConfiguration is
// auto-detected only when @SpringBootTest supplies no explicit `classes`, and this class pins
// classes = Application.class.
@SpringBootTest(classes = Application.class)
@Import(BillingApiIntegrationTest.EventRecorderConfiguration.class)
@Testcontainers
class BillingApiIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static WireMockServer wireMock;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    // M5: routes payment's MobileMoneyGatewayAdapter at the in-process WireMock standing in for
    // the mobile-money rail, same mechanism policyloan.LoanDisbursementEndToEndTest already uses
    // for its own real request/confirm chain proof.
    @DynamicPropertySource
    static void mobileMoneyProperties(DynamicPropertyRegistry registry) {
        registry.add("mobile-money.base-url", () -> wireMock.baseUrl());
    }

    @BeforeAll
    static void applyMigrations() throws Exception {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/party/V1__create_party_schema.sql",
            "db-migrations/party/V2__individual_person_record.sql",
            "db-migrations/party/V4__registered_by_agent.sql",
            "db-migrations/party/V5__registered_by_name.sql",
            "db-migrations/party/V6__client_reference.sql",
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql",
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
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/refdata/V3__seed_billing_parameters.sql",
            "db-migrations/refdata/V8__ifrs17_branches_and_channels.sql",
            "db-migrations/policyloan/V1__create_policyloan_schema.sql",
            "db-migrations/policyloan/V3__money_check_constraints.sql",
            "db-migrations/policyloan/V7__q4_2026_partitions.sql",
            "db-migrations/billing/V1__create_billing_schema.sql",
            "db-migrations/billing/V2__grants_rls_money_checks_and_notification_columns.sql",
            "db-migrations/billing/V3__amount_paid.sql",
            "db-migrations/billing/V5__single_premium_invoice.sql",
            "db-migrations/billing/V6__premium_credit.sql",
            "db-migrations/billing/V7__policy_inception_invoice.sql",
            "db-migrations/billing/V8__schedule_premium_paying_until.sql",
            "db-migrations/billing/V9__schedules_due_for_invoicing.sql",
            "db-migrations/payment/V1__create_payment_schema.sql",
            "db-migrations/payment/V2__grants_rls_money_checks_version_and_tenant_scoped_registries.sql",
            // M5 final-review fix wave: V3's callback resolvers and V4's widened status CHECK
            // (IN_DOUBT). This class drives real payment.PaymentRequestListener chains, so its
            // payment schema must match production's -- without V4 an indeterminate collection
            // outcome would fail the CHECK here while working in a real deployment.
            "db-migrations/payment/V3__inbound_callback_tenant_resolver.sql",
            "db-migrations/payment/V4__in_doubt_status_and_id_based_callback_resolvers.sql",
            "db-migrations/payment/V6__disbursement_method.sql",
            "db-migrations/payment/V7__q4_2026_partitions.sql",
            "db-migrations/payment/V8__benefit_payout_purposes.sql",
            "db-migrations/payment/V9__account_purposes.sql");
    }

    @AfterAll
    static void stopGateway() { wireMock.stop(); }

    @AfterEach
    void resetWireMock() { wireMock.resetAll(); }

    /**
     * M7: this class had no way to assert on published events, so Task 7 adds the minimum one --
     * an AFTER_COMMIT recorder, the same phase the real consumers subscribe on, so it sees exactly
     * what they see and nothing that was rolled back.
     */
    @TestConfiguration
    static class EventRecorderConfiguration {
        @Bean
        EventRecorder billingTestEventRecorder() { return new EventRecorder(); }
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

    @Autowired private EventRecorder eventRecorder;

    @Autowired private PolicyApi policyApi;
    @Autowired private BillingApi billingApi;
    @Autowired private tz.co.nlolo.lifeplatform.party.api.PartyApi partyApi;
    @Autowired private tz.co.nlolo.lifeplatform.product.api.ProductApi productApi;
    @Autowired private BillingScheduleRepository billingScheduleRepository;
    @Autowired private BillingApiImpl billingApiImpl;
    @Autowired private ArrearsNotificationSweep arrearsNotificationSweep;
    @Autowired private PremiumInvoiceRepository premiumInvoiceRepository;
    @Autowired private FieldReceiptRepository fieldReceiptRepository;
    @Autowired private DataSource dataSource;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private ObjectMapper objectMapper;

    private record Fixture(UUID applicantId, UUID productId, UUID productVersionId) {}

    private Fixture buildFixture(UUID tenantId, String productCode) {
        return buildFixture(tenantId, productCode, tz.co.nlolo.lifeplatform.product.api.ProductCategory.TERM_LIFE);
    }

    private Fixture buildFixture(UUID tenantId, String productCode, tz.co.nlolo.lifeplatform.product.api.ProductCategory category) {
        TenantContext.set(tenantId);
        tz.co.nlolo.lifeplatform.party.api.PartyView applicant = partyApi.registerIndividual(
            "Billing Test Applicant " + productCode, LocalDate.of(1990, 1, 1),
            "+25571300" + String.format("%04d", Math.abs(productCode.hashCode() % 10000)), null, "test-agent");
        tz.co.nlolo.lifeplatform.product.api.ProductSummaryView product = productApi.createProduct(
            productCode, "Billing Test Product", category, "TZS", "actuary");
        productApi.publishVersion(product.productId(), tz.co.nlolo.lifeplatform.product.api.IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new tz.co.nlolo.lifeplatform.product.api.ProductApi.RatingFactorInput(
                        tz.co.nlolo.lifeplatform.product.api.FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new tz.co.nlolo.lifeplatform.product.api.ProductApi.RatingFactorInput(
                        tz.co.nlolo.lifeplatform.product.api.FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new tz.co.nlolo.lifeplatform.product.api.ProductApi.BenefitInput(
                        tz.co.nlolo.lifeplatform.product.api.BenefitType.DEATH, tz.co.nlolo.lifeplatform.product.api.BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary");
        tz.co.nlolo.lifeplatform.product.api.ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        return new Fixture(applicant.partyId(), product.productId(), snapshot.productVersionId());
    }

    private String issueDirectly(UUID tenantId, Fixture fixture, BigDecimal premiumAmount, String premiumFrequency) {
        TenantContext.set(tenantId);
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(fixture.applicantId(), fixture.productId(), fixture.productVersionId(),
            new BigDecimal("1000000"), "TZS", premiumAmount, "TZS", premiumFrequency, null, List.of(), "Direct issuance test");
        String issuedPolicyNumber = policyApi.issuePolicy(UUID.randomUUID(), request, "test-staff").policyNumber();
        // Cover starts with the first premium. This fixture needs a policy on risk.
        policyApi.activateOnFirstPremium(issuedPolicyNumber);
        return issuedPolicyNumber;
    }

    /** Issue a termed policy commencing today, so billing has a paying-end to stop at (D1). */
    private String issueTermedDirectly(UUID tenantId, Fixture fixture, BigDecimal premiumAmount,
                                        String premiumFrequency, int termMonths) {
        TenantContext.set(tenantId);
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(fixture.applicantId(), fixture.productId(), fixture.productVersionId(),
            new BigDecimal("1000000"), "TZS", premiumAmount, "TZS", premiumFrequency, null, List.of(), "Direct issuance test",
            LocalDate.now(), termMonths, null, null, null);
        String issuedPolicyNumber = policyApi.issuePolicy(UUID.randomUUID(), request, "test-staff").policyNumber();
        policyApi.activateOnFirstPremium(issuedPolicyNumber);
        return issuedPolicyNumber;
    }

    /** A policy whose term ran out today: commenced a year ago on a 12-month term (D2 expiry). */
    private String issueMaturedDirectly(UUID tenantId, Fixture fixture) {
        TenantContext.set(tenantId);
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(fixture.applicantId(), fixture.productId(), fixture.productVersionId(),
            new BigDecimal("1000000"), "TZS", new BigDecimal("40000.00"), "TZS", "MONTHLY", null, List.of(), "Direct issuance test",
            LocalDate.now().minusMonths(12), 12, null, null, null);
        String issuedPolicyNumber = policyApi.issuePolicy(UUID.randomUUID(), request, "test-staff").policyNumber();
        policyApi.activateOnFirstPremium(issuedPolicyNumber);
        return issuedPolicyNumber;
    }

    @Test
    void expiringAMaturedPolicyMovesItToExpiredAndStopsBilling() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "BILL-EXPIRE");
        String policyNumber = issueMaturedDirectly(tenantId, fixture);

        TenantContext.set(tenantId);
        assertThat(billingScheduleRepository.findByPolicyNumberAndTenantIdAndStatus(policyNumber, tenantId, "ACTIVE"))
            .isPresent();

        // The selector picks a matured term policy that carries no maturity benefit.
        assertThat(policyNumbersDueToExpire()).contains(policyNumber);

        eventRecorder.clear();
        policyApi.expirePolicy(policyNumber);

        TenantContext.set(tenantId);
        assertThat(policyApi.getPolicy(policyNumber).status()).isEqualTo(PolicyStatus.EXPIRED);
        assertThat(eventRecorder.ofType("policy.PolicyExpired")).hasSize(1);
        // Billing stops: the schedule is terminated by the PolicyExpired listener.
        assertThat(billingScheduleRepository.findByPolicyNumberAndTenantIdAndStatus(policyNumber, tenantId, "TERMINATED"))
            .isPresent();
        // And it is no longer offered for expiry.
        assertThat(policyNumbersDueToExpire()).doesNotContain(policyNumber);
    }

    @Test
    void aMaturedPolicyThatCarriesAMaturityBenefitIsNotSweptToExpired() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "BILL-NO-EXPIRE");
        String policyNumber = issueMaturedDirectly(tenantId, fixture);

        // A maturity benefit means the policy matures (pays), it does not expire. Simulate one by
        // adding an active MATURITY coverage row -- issuance would write it for an endowment.
        try (Connection connection = dataSource.getConnection();
             PreparedStatement setTenant = connection.prepareStatement("SELECT set_config('app.current_tenant_id', ?, false)")) {
            setTenant.setString(1, tenantId.toString());
            setTenant.execute();
            try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO policy.coverage (tenant_id, policy_number, benefit_type, sum_assured_amount, sum_assured_currency, active) "
                    + "VALUES (?, ?, 'MATURITY', 1000000, 'TZS', true)")) {
                insert.setObject(1, tenantId);
                insert.setString(2, policyNumber);
                insert.executeUpdate();
            }
        }

        // The selector's NOT EXISTS clause excludes it: the expiry drain leaves it for maturity.
        assertThat(policyNumbersDueToExpire()).doesNotContain(policyNumber);
    }

    /** The expiry selector's output, read directly -- the ids CoverExpiryDrain would act on. */
    private List<String> policyNumbersDueToExpire() throws Exception {
        List<String> result = new java.util.ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement("SELECT policy_number FROM policy.policies_due_to_expire()");
             var rs = select.executeQuery()) {
            while (rs.next()) {
                result.add(rs.getString(1));
            }
        }
        return result;
    }

    @Test
    void aSixMonthPolicyIsBilledForSixMonthsNotTwelve() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "BILL-6MO");
        String policyNumber = issueTermedDirectly(tenantId, fixture, new BigDecimal("40000.00"), "MONTHLY", 6);

        TenantContext.set(tenantId);
        List<PremiumInvoice> invoices = premiumInvoiceRepository.findByPolicyNumberAndTenantIdOrderByDueDate(policyNumber, tenantId);
        // Due one month after issue through the sixth month: six invoices, not the twelve the
        // fixed horizon would have raised. The paying end (commencement + 6) is the bound.
        assertThat(invoices).hasSize(6);
        assertThat(invoices).allSatisfy(i ->
            assertThat(i.getDueDate()).isBeforeOrEqualTo(LocalDate.now().plusMonths(6)));
    }

    @Test
    void rollForwardExtendsBillingUpToThePayingEndAndThenStops() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "BILL-ROLL");
        // The recorder is shared across the class; clear it so the event count below is this
        // policy's alone (12 raised at issue + 12 rolled forward = 24).
        eventRecorder.clear();
        // 24-month term: the horizon caps the first batch at 12, leaving 12 to roll forward.
        String policyNumber = issueTermedDirectly(tenantId, fixture, new BigDecimal("40000.00"), "MONTHLY", 24);

        TenantContext.set(tenantId);
        BillingSchedule schedule = billingScheduleRepository.findByPolicyNumberAndTenantId(policyNumber, tenantId).get(0);
        assertThat(premiumInvoiceRepository.findByPolicyNumberAndTenantIdOrderByDueDate(policyNumber, tenantId)).hasSize(12);

        // The drain would call this once the schedule's due date entered the horizon; here it is
        // driven directly. It raises the next batch, bounded by the paying end (commencement + 24).
        billingApiImpl.rollForward(schedule.getBillingScheduleId());
        assertThat(premiumInvoiceRepository.findByPolicyNumberAndTenantIdOrderByDueDate(policyNumber, tenantId)).hasSize(24);

        // A second call raises nothing: the schedule's next due date has passed the paying end.
        billingApiImpl.rollForward(schedule.getBillingScheduleId());
        assertThat(premiumInvoiceRepository.findByPolicyNumberAndTenantIdOrderByDueDate(policyNumber, tenantId)).hasSize(24);

        // Every roll published an invoice-generated event, the one finaccounting posts against.
        assertThat(eventRecorder.ofType("billing.PremiumInvoiceGenerated")).hasSize(24);
    }

    @Test
    void issuingAPolicyGeneratesAnActiveScheduleAndInvoicesAhead() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "BILLING-SCHEDULE-01");
        String policyNumber = issueDirectly(tenantId, fixture, new BigDecimal("15000.00"), "MONTHLY");

        List<InvoiceView> invoices = billingApi.listInvoices(policyNumber, null);

        assertThat(invoices).isNotEmpty();
        assertThat(invoices).allSatisfy(inv -> assertThat(inv.amount()).isEqualByComparingTo(new BigDecimal("15000.00")));
        // 12-month horizon / MONTHLY frequency = 12 invoices generated ahead.
        assertThat(invoices).hasSize(12);

        InvoiceView nextDue = billingApi.getNextDueInvoice(policyNumber);
        assertThat(nextDue.dueDate()).isEqualTo(invoices.get(0).dueDate());
    }

    /**
     * Regression test for a real bug found in Task 8's self-review: an earlier draft of
     * BillingApiImpl.regenerateScheduleForEndorsement unconditionally TERMINATED the active
     * schedule with nothing to replace it, and policy.PolicyEndorsed fires for EVERY endorsement
     * type (an unrestricted free-form string, e.g. an address change) -- so any single
     * endorsement on any policy would have silently stopped all future invoicing. This proves
     * the fix: an endorsement leaves the schedule genuinely untouched.
     */
    @Test
    void applyingAnEndorsementLeavesTheActiveScheduleUntouched() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "BILLING-ENDORSE-01");
        String policyNumber = issueDirectly(tenantId, fixture, new BigDecimal("15000.00"), "MONTHLY");

        BillingSchedule scheduleBeforeEndorsement = billingScheduleRepository.findByPolicyNumberAndTenantIdAndStatus(policyNumber, tenantId, "ACTIVE")
            .orElseThrow(() -> new AssertionError("expected an ACTIVE schedule after issuance"));

        TenantContext.set(tenantId);
        policyApi.applyEndorsement(policyNumber,
            new PolicyApi.EndorsementInput("ADDRESS_CHANGE", LocalDate.now(), java.util.Map.of("newAddress", "Dar es Salaam")),
            "test-agent");

        BillingSchedule scheduleAfterEndorsement = billingScheduleRepository.findByPolicyNumberAndTenantIdAndStatus(policyNumber, tenantId, "ACTIVE")
            .orElseThrow(() -> new AssertionError("expected the SAME schedule to still be ACTIVE after an unrelated endorsement -- " +
                "if this fails, the schedule was terminated with nothing to replace it"));
        assertThat(scheduleAfterEndorsement.getBillingScheduleId()).isEqualTo(scheduleBeforeEndorsement.getBillingScheduleId());
        assertThat(billingApi.listInvoices(policyNumber, null)).hasSize(12);
    }

    @Test
    void suspendingAPolicyPausesItsScheduleAndResumingReactivatesIt() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "BILLING-SUSPEND-RESUME-01", tz.co.nlolo.lifeplatform.product.api.ProductCategory.GROUP_LIFE);
        String policyNumber = issueDirectly(tenantId, fixture, new BigDecimal("15000.00"), "MONTHLY");

        BillingSchedule activeSchedule = billingScheduleRepository.findByPolicyNumberAndTenantIdAndStatus(policyNumber, tenantId, "ACTIVE")
            .orElseThrow(() -> new AssertionError("expected an ACTIVE schedule after issuance"));
        LocalDate horizonBeforeSuspend = activeSchedule.getNextDueDate();

        policyApi.suspendPolicy(policyNumber, "investigation", "test-staff");
        assertThat(billingScheduleRepository.findByPolicyNumberAndTenantIdAndStatus(policyNumber, tenantId, "SUSPENDED")).isPresent();
        assertThat(billingScheduleRepository.findByPolicyNumberAndTenantIdAndStatus(policyNumber, tenantId, "ACTIVE")).isEmpty();

        policyApi.resumeSuspendedPolicy(policyNumber, "test-staff");
        BillingSchedule resumedSchedule = billingScheduleRepository.findByPolicyNumberAndTenantIdAndStatus(policyNumber, tenantId, "ACTIVE")
            .orElseThrow(() -> new AssertionError("expected the schedule to be ACTIVE again after resume"));
        // Proves resumeScheduleAfterSuspension's generateInvoicesAhead call actually ran again
        // (not just that the status flipped back) -- the horizon must have advanced from where
        // it was left at suspension time, not merely been left untouched.
        assertThat(resumedSchedule.getNextDueDate()).isAfter(horizonBeforeSuspend);
    }

    @Test
    void dunningLevelFiveRecommendationEventuallyLapsesThePolicy() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "BILLING-LAPSE-RECOMMEND-01");
        String policyNumber = issueDirectly(tenantId, fixture, new BigDecimal("15000.00"), "MONTHLY");

        List<InvoiceView> invoices = billingApi.listInvoices(policyNumber, null);
        UUID firstInvoiceId = invoices.get(0).invoiceId();

        // Manually insert an ArrearsCase row at dunning_level=5 via raw JDBC -- this test proves
        // the NOTIFICATION -> LAPSE wiring specifically, not the day-threshold escalation math
        // (Task 5's own SQL sweep test proves that separately). Seeding directly here means this
        // test does not have to wait out 30 real days to reach level 5.
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                 "INSERT INTO billing.arrears_case (tenant_id, invoice_id, policy_number, dunning_level, last_notified_dunning_level) " +
                 "VALUES (?, ?, ?, 5, 0)")) {
            insert.setObject(1, tenantId);
            insert.setObject(2, firstInvoiceId);
            insert.setString(3, policyNumber);
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }

        TenantContext.set(tenantId);
        arrearsNotificationSweep.publishPending(tenantId);

        assertThat(policyApi.getPolicy(policyNumber).status()).isEqualTo(PolicyStatus.LAPSED);
    }

    /**
     * The test the original gap needed: not "does the sweep work" but "does anything call it".
     *
     * <p>{@code publishPendingNotifications} was written, covered by the two tests above, and
     * had zero production callers -- so in a deployed environment {@code dunning_level} would
     * climb to 5 while no event was ever published and automatic lapse never fired. Every
     * existing test invoked the bean directly, which is exactly why none of them noticed.
     *
     * <p>So this one goes through {@code BillingApi.listInvoices} -- a real request path, the
     * one the console calls on every policy record -- and asserts the event came out. It never
     * names the sweep. Unhook it from {@code listInvoices} and this fails; keep the sweep
     * perfect and unreachable and this fails too.
     */
    @Test
    void readingAPolicysInvoicesCarriesThePendingArrearsNotificationForward() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "BILLING-SWEEP-WIRED-01");
        String policyNumber = issueDirectly(tenantId, fixture, new BigDecimal("40000.00"), "MONTHLY");

        TenantContext.set(tenantId);
        UUID invoiceId = billingApi.listInvoices(policyNumber, null).get(0).invoiceId();

        // Seeded exactly as billing.sweep_billing_state() would leave it: level 3 reached, and
        // nothing announced yet. Level 3 rather than 5 so this proves the customer-facing
        // PremiumOverdue path rather than the lapse path the test above already covers.
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                 "INSERT INTO billing.arrears_case (tenant_id, invoice_id, policy_number, dunning_level, last_notified_dunning_level) " +
                 "VALUES (?, ?, ?, 3, 0)")) {
            insert.setObject(1, tenantId);
            insert.setObject(2, invoiceId);
            insert.setString(3, policyNumber);
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }

        Instant before = Instant.now();

        // The whole point: an ordinary read, with no mention of any sweep.
        billingApi.listInvoices(policyNumber, null);

        // Asserted through the audit journal rather than through the column, because
        // last_notified_dunning_level advancing only proves bookkeeping ran -- the journal
        // proves an event was genuinely PUBLISHED, which is the thing that was missing. It also
        // proves the sweep's @Transactional survived: AFTER_COMMIT listeners drop everything
        // published outside a transaction, which is what self-invoking this from listInvoices
        // would silently have caused.
        List<AuditLogEntry> auditRows = auditLogRepository.findByTenantIdAndEventTypeAndOccurredAtBetween(
            tenantId, "billing.PremiumOverdue", before.minusSeconds(5), Instant.now().plusSeconds(5));
        assertThat(auditRows).hasSize(1);
        JsonNode payload = objectMapper.readTree(auditRows.get(0).getPayload());
        assertThat(payload.path("policyNumber").asText()).isEqualTo(policyNumber);
        assertThat(payload.path("dunningLevel").asInt()).isEqualTo(3);

        // And a second read does not re-announce it. Without this, hanging the sweep off a
        // frequently-called read would re-notify a customer on every page view.
        Instant beforeSecondRead = Instant.now();
        billingApi.listInvoices(policyNumber, null);
        assertThat(auditLogRepository.findByTenantIdAndEventTypeAndOccurredAtBetween(
            tenantId, "billing.PremiumOverdue", beforeSecondRead, Instant.now().plusSeconds(5))).isEmpty();
    }

    @Test
    void capturingAFieldReceiptThenSweepingAfterTheSlaWindowPublishesReconciliationOverdue() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "BILLING-RECEIPT-SLA-01");
        String policyNumber = issueDirectly(tenantId, fixture, new BigDecimal("15000.00"), "MONTHLY");

        BillingApi.FieldReceiptResult result = billingApi.captureFieldReceipt(
            UUID.randomUUID(), policyNumber, new BigDecimal("15000.00"), "TZS",
            "client-key-" + UUID.randomUUID(), Instant.now());
        assertThat(result.status()).isEqualTo("PENDING_RECONCILIATION");

        // This test proves the JAVA HALF of the split design (notification) -- the SQL half
        // (sweep_billing_state()'s own SLA-breach UPDATE) is proven separately by
        // BillingSweepPsqlTest. So the status flip to RECONCILIATION_OVERDUE is seeded directly
        // here, exactly as sweep_billing_state() itself would have performed it, isolating
        // ArrearsNotificationSweep as the one thing under test.
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(
                 "UPDATE billing.field_receipt SET status = 'RECONCILIATION_OVERDUE' WHERE receipt_id = ?")) {
            update.setObject(1, result.receiptId());
            assertThat(update.executeUpdate()).isEqualTo(1);
        }

        TenantContext.set(tenantId);
        Instant before = Instant.now();
        int published = arrearsNotificationSweep.publishPending(tenantId);
        assertThat(published).isGreaterThanOrEqualTo(1);

        // Same audit-log-query falsifiability idiom as policy.PolicyApiIntegrationTest's own
        // event-publication tests -- proves the event was genuinely published, not merely that
        // notifiedOverdueAt got set.
        List<AuditLogEntry> auditRows = auditLogRepository.findByTenantIdAndEventTypeAndOccurredAtBetween(
            tenantId, "billing.FieldReceiptReconciliationOverdue", before.minusSeconds(5), Instant.now().plusSeconds(5));
        assertThat(auditRows).hasSize(1);
        JsonNode payload = objectMapper.readTree(auditRows.get(0).getPayload());
        assertThat(payload.path("receiptId").asText()).isEqualTo(result.receiptId().toString());

        // Confirms the notification is idempotent, not re-fired on every sweep.
        assertThat(arrearsNotificationSweep.publishPending(tenantId)).isEqualTo(0);
    }

    // --- M5: the money-in loop -----------------------------------------------------------------

    /**
     * Task 7's real end-to-end proof that billing's premium-collection loop is actually closed:
     * {@code billingApi.requestPaymentForInvoice} (real API) -> {@code billing.PaymentRequested}
     * (real event) -> {@code payment.PaymentRequestListener} (real listener) -> the mobile-money
     * gateway (in-process WireMock stand-in) -> {@code payment.PaymentConfirmed} (real event) ->
     * {@code billing.application.PaymentEventListener} (this task's new listener) ->
     * {@code BillingApi.applyConfirmedPayment}. No call to {@code applyConfirmedPayment} anywhere
     * in this test -- mirrors {@code LoanDisbursementEndToEndTest}'s structure exactly.
     */
    @Test
    void requestingPaymentForAnInvoiceRunsTheRealChainThroughToPaid() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "BILLING-PAYMENT-PAID-01");
        String policyNumber = issueDirectly(tenantId, fixture, new BigDecimal("15000.00"), "MONTHLY");
        InvoiceView invoice = billingApi.listInvoices(policyNumber, null).get(0);

        wireMock.stubFor(post(urlPathEqualTo("/collect")).willReturn(okJson(
            "{\"status\":\"ACCEPTED\",\"gatewayReference\":\"MM-BILLING-PAID-01\"}")));

        TenantContext.set(tenantId);
        // By the time this call returns, the AFTER_COMMIT chain above has already run
        // synchronously and committed -- no await/sleep, same reasoning as
        // LoanDisbursementEndToEndTest's own class-level Javadoc.
        billingApi.requestPaymentForInvoice(invoice.invoiceId(), "MPESA-0712340001", "billing-it-paid-01-attempt-1");

        PremiumInvoice reloaded = premiumInvoiceRepository.findByInvoiceIdAndTenantId(invoice.invoiceId(), tenantId)
            .orElseThrow(() -> new AssertionError("expected the invoice to still exist"));
        assertThat(reloaded.getStatus()).isEqualTo("PAID");
        assertThat(reloaded.getAmountPaid()).isEqualByComparingTo("15000.00");

        wireMock.verify(exactly(1), postRequestedFor(urlPathEqualTo("/collect")));
    }

    /**
     * Review fix (I1), the domain-level proof. Counts REAL gateway calls, because that is the only
     * thing that distinguishes the fixed behaviour from the broken behaviour: before the fix, both
     * the first attempt and the retry returned HTTP 202 / completed normally at the Java level, and
     * only WireMock's request count revealed that the retry reached the rail zero times.
     *
     * <p>Three attempts against ONE invoice, in one test, so the two rules are proven against each
     * other rather than in isolation:
     * <ol>
     *   <li>attempt 1 with key A -> the rail is called (1 call);</li>
     *   <li>attempt 2 with key A AGAIN -> deduplicated by payment's
     *       {@code (tenant_id, idempotency_key)} registry, the rail is NOT called again (still 1) --
     *       so this fix did not simply disable idempotency, which is the obvious way to "fix" the
     *       original bug and would be much worse (a double-clicked operator collects twice);</li>
     *   <li>attempt 3 with a NEW key B -> a genuine new attempt, the rail IS called again (2) --
     *       the behaviour that was impossible before, forever, for the entire life of the invoice.</li>
     * </ol>
     * The rail declines every attempt here ({@code INSUFFICIENT_FUNDS}), which is exactly the
     * scenario an operator retries after, and keeps the invoice out of a terminal PAID state so all
     * three attempts are individually meaningful.
     */
    @Test
    void aRetryWithANewIdempotencyKeyReachesTheRailAgainWhileARepeatedKeyDoesNot() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "BILLING-PAYMENT-RETRY-01");
        String policyNumber = issueDirectly(tenantId, fixture, new BigDecimal("15000.00"), "MONTHLY");
        InvoiceView invoice = billingApi.listInvoices(policyNumber, null).get(0);

        wireMock.stubFor(post(urlPathEqualTo("/collect")).willReturn(okJson(
            "{\"status\":\"REJECTED\",\"reason\":\"INSUFFICIENT_FUNDS\"}")));

        TenantContext.set(tenantId);
        String keyA = "billing-it-retry-01-attempt-1";
        String keyB = "billing-it-retry-01-attempt-2";

        billingApi.requestPaymentForInvoice(invoice.invoiceId(), "MPESA-0712340002", keyA);
        wireMock.verify(exactly(1), postRequestedFor(urlPathEqualTo("/collect")));

        // Same key = same intent. Must NOT reach the rail a second time.
        billingApi.requestPaymentForInvoice(invoice.invoiceId(), "MPESA-0712340002", keyA);
        wireMock.verify(exactly(1), postRequestedFor(urlPathEqualTo("/collect")));

        // New key = new attempt. MUST reach the rail. This is the line that fails outright if
        // idempotencyKey goes back to being derived from invoiceId.
        billingApi.requestPaymentForInvoice(invoice.invoiceId(), "MPESA-0712340002", keyB);
        wireMock.verify(exactly(2), postRequestedFor(urlPathEqualTo("/collect")));
    }

    /** Review fix (I1): the key is validated in the published API method too, not only in the
     * controller -- a future non-HTTP caller (a batch collection run) must not be able to slip a
     * blank key through to payment, where PaymentRequestListener.requireKey would throw inside an
     * AFTER_COMMIT listener and be swallowed, presenting as a successful request that reached the
     * rail zero times. */
    @Test
    void requestPaymentForInvoiceRejectsABlankIdempotencyKeyAtTheApiLevel() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "BILLING-PAYMENT-RETRY-02");
        String policyNumber = issueDirectly(tenantId, fixture, new BigDecimal("15000.00"), "MONTHLY");
        InvoiceView invoice = billingApi.listInvoices(policyNumber, null).get(0);

        wireMock.stubFor(post(urlPathEqualTo("/collect")).willReturn(okJson(
            "{\"status\":\"ACCEPTED\",\"gatewayReference\":\"MM-SHOULD-NOT-HAPPEN\"}")));

        TenantContext.set(tenantId);
        assertThatThrownBy(() -> billingApi.requestPaymentForInvoice(invoice.invoiceId(), "MPESA-0712340003", "  "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Idempotency-Key");
        assertThatThrownBy(() -> billingApi.requestPaymentForInvoice(invoice.invoiceId(), "MPESA-0712340003", null))
            .isInstanceOf(IllegalArgumentException.class);

        // Falsifiable: nothing was published and therefore nothing reached the rail.
        wireMock.verify(exactly(0), postRequestedFor(urlPathEqualTo("/collect")));
    }

    @Test
    void anUnderPaymentLandsPartiallyPaidWithTheRealAmountPaidRecorded() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "BILLING-PAYMENT-PARTIAL-01");
        String policyNumber = issueDirectly(tenantId, fixture, new BigDecimal("15000.00"), "MONTHLY");
        InvoiceView invoice = billingApi.listInvoices(policyNumber, null).get(0);

        TenantContext.set(tenantId);
        // requestPaymentForInvoice's own chain always requests the FULL invoice amount, so a
        // genuine under-payment can only ever arrive as a partial gateway confirmation -- this
        // drives applyConfirmedPayment directly to isolate its own PARTIALLY_PAID decision, the
        // same isolation capturingAFieldReceiptThenSweepingAfterTheSlaWindowPublishesReconciliationOverdue
        // already uses for the Java half of a two-part chain above.
        billingApi.applyConfirmedPayment(invoice.invoiceId(), new BigDecimal("5000.00"), "TZS", "MM-PARTIAL-01");

        PremiumInvoice reloaded = premiumInvoiceRepository.findByInvoiceIdAndTenantId(invoice.invoiceId(), tenantId)
            .orElseThrow(() -> new AssertionError("expected the invoice to still exist"));
        assertThat(reloaded.getStatus()).isEqualTo("PARTIALLY_PAID");
        // The falsifiable half: the ACTUAL amountPaid value, not just the status string -- a
        // buggy applyPayment that flips status without ever writing amountPaid would still pass
        // an assertion that only checked the status.
        assertThat(reloaded.getAmountPaid()).isEqualByComparingTo("5000.00");
    }

    // =========================================================================================
    // M7 Task 7 -- billing.PremiumCollected. Before this milestone applyConfirmedPayment recorded
    // a premium as paid and published NOTHING, so no module could react to a collection.
    // =========================================================================================

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payloadOf(DomainEventEnvelope<?> envelope) {
        return (Map<String, Object>) envelope.payload();
    }

    @Test
    void collectingAnInvoiceInFullPublishesExactlyOnePremiumCollectedCarryingThePolicyNumber() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "BILLING-COLLECTED-01");
        String policyNumber = issueDirectly(tenantId, fixture, new BigDecimal("15000.00"), "MONTHLY");
        InvoiceView invoice = billingApi.listInvoices(policyNumber, null).get(0);

        TenantContext.set(tenantId);
        eventRecorder.clear();
        billingApi.applyConfirmedPayment(invoice.invoiceId(), new BigDecimal("15000.00"), "TZS", "MM-COLLECTED-01");

        List<DomainEventEnvelope<?>> collected = eventRecorder.ofType("billing.PremiumCollected");
        assertThat(collected).hasSize(1);
        assertThat(collected.get(0).tenantId()).isEqualTo(tenantId);
        Map<String, Object> payload = payloadOf(collected.get(0));
        // policyNumber is the whole reason this event exists -- payment.PaymentConfirmed carries
        // only sourceRef = invoiceId, leaving distribution unable to attribute the collection.
        assertThat(payload.get("policyNumber")).isEqualTo(policyNumber);
        assertThat(payload.get("invoiceId")).isEqualTo(invoice.invoiceId());
        assertThat((Map<String, Object>) payload.get("amount"))
            .containsEntry("amount", "15000.00")
            .containsEntry("currencyCode", "TZS");
        assertThat(payload.get("collectedAt")).isNotNull();
    }

    @Test
    void anUnderPaymentPublishesNoPremiumCollectedUntilTheInvoiceIsToppedUpToPaid() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "BILLING-COLLECTED-PARTIAL-01");
        String policyNumber = issueDirectly(tenantId, fixture, new BigDecimal("15000.00"), "MONTHLY");
        InvoiceView invoice = billingApi.listInvoices(policyNumber, null).get(0);

        TenantContext.set(tenantId);
        eventRecorder.clear();
        billingApi.applyConfirmedPayment(invoice.invoiceId(), new BigDecimal("5000.00"), "TZS", "MM-PART-A");

        // A PARTIALLY_PAID invoice is not a collected premium. Consumers dedupe on invoiceId, so
        // emitting here would attribute renewal commission to the 5000 part-amount and then
        // silently swallow the completing 10000 -- the agent underpaid, permanently.
        assertThat(eventRecorder.ofType("billing.PremiumCollected")).isEmpty();

        billingApi.applyConfirmedPayment(invoice.invoiceId(), new BigDecimal("10000.00"), "TZS", "MM-PART-B");

        List<DomainEventEnvelope<?>> collected = eventRecorder.ofType("billing.PremiumCollected");
        assertThat(collected).as("exactly one event, on the edge into PAID").hasSize(1);
        // The FULL premium, not the 10000 that happened to complete it.
        assertThat((Map<String, Object>) payloadOf(collected.get(0)).get("amount"))
            .containsEntry("amount", "15000.00");
    }

    @Test
    void aRepeatedPaymentAgainstAnAlreadyPaidInvoicePublishesNoSecondPremiumCollected() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "BILLING-COLLECTED-REPEAT-01");
        String policyNumber = issueDirectly(tenantId, fixture, new BigDecimal("15000.00"), "MONTHLY");
        InvoiceView invoice = billingApi.listInvoices(policyNumber, null).get(0);

        TenantContext.set(tenantId);
        eventRecorder.clear();
        billingApi.applyConfirmedPayment(invoice.invoiceId(), new BigDecimal("15000.00"), "TZS", "MM-REPEAT-A");
        assertThat(eventRecorder.ofType("billing.PremiumCollected")).hasSize(1);

        // applyPayment is a no-op once PAID (PremiumInvoice:91-93) -- it writes nothing, so a
        // second event here would announce a collection that never touched amountPaid.
        billingApi.applyConfirmedPayment(invoice.invoiceId(), new BigDecimal("15000.00"), "TZS", "MM-REPEAT-B");
        assertThat(eventRecorder.ofType("billing.PremiumCollected")).hasSize(1);
    }

    @Autowired private org.springframework.context.ApplicationEventPublisher applicationEventPublisher;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

    /** A policy event, committed, so billing's AFTER_COMMIT listener reacts exactly as it does in production. */
    private void publishCommitted(UUID tenantId, String type, Map<String, Object> payload) {
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(s ->
            applicationEventPublisher.publishEvent(tz.co.nlolo.lifeplatform.DomainEventEnvelope.of(type, tenantId, payload)));
    }

    private Map<UUID, String> statusById(UUID tenantId, String policyNumber) {
        TenantContext.set(tenantId);
        return premiumInvoiceRepository.findByPolicyNumberAndTenantIdOrderByDueDate(policyNumber, tenantId).stream()
            .collect(java.util.stream.Collectors.toMap(PremiumInvoice::getInvoiceId, PremiumInvoice::getStatus));
    }

    @Test
    void aWaiverCarriesTheAmountStillOutstandingSoTheLedgerCanReverseIt() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "BILLING-WAIVER-AMOUNT-01");
        String policyNumber = issueDirectly(tenantId, fixture, new BigDecimal("15000.00"), "MONTHLY");
        InvoiceView invoice = billingApi.listInvoices(policyNumber, null).get(0);

        TenantContext.set(tenantId);
        eventRecorder.clear();
        billingApi.waiveInvoice(invoice.invoiceId(), "Hardship write-off", "finance-one");

        List<DomainEventEnvelope<?>> waived = eventRecorder.ofType("billing.InvoiceWaived");
        assertThat(waived).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) waived.get(0).payload();
        assertThat(payload.get("amount")).isEqualTo(Map.of("amount", "15000.00", "currencyCode", "TZS"));
    }

    @Test
    void aSurrenderWaivesTheInvoicesForCoverItNoLongerGivesButLeavesItsArrears() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "BILLING-SURRENDER-WAIVE-01");
        String policyNumber = issueDirectly(tenantId, fixture, new BigDecimal("15000.00"), "MONTHLY");
        TenantContext.set(tenantId);
        List<PremiumInvoice> invoices = premiumInvoiceRepository.findByPolicyNumberAndTenantIdOrderByDueDate(policyNumber, tenantId);
        assertThat(invoices).as("rolling billing raises instalments ahead, or this proves nothing").hasSizeGreaterThan(1);
        LocalDate endedOn = invoices.get(1).getDueDate();

        eventRecorder.clear();
        publishCommitted(tenantId, "policy.PolicySurrendered", Map.of("policyNumber", policyNumber,
            "surrenderedAt", endedOn.atTime(10, 0).atZone(java.time.ZoneId.of("Africa/Dar_es_Salaam")).toInstant().toString()));

        Map<UUID, String> after = statusById(tenantId, policyNumber);
        // The first instalment fell due before the policy ended: cover was given for it, so it stays owed.
        assertThat(after.get(invoices.get(0).getInvoiceId())).isEqualTo("DUE");
        // Every instalment from the day it ended on is waived, and each one's receivable is published for reversal.
        invoices.subList(1, invoices.size()).forEach(i -> assertThat(after.get(i.getInvoiceId())).isEqualTo("WAIVED"));
        assertThat(eventRecorder.ofType("billing.InvoiceWaived")).hasSize(invoices.size() - 1);
        assertThat(billingScheduleRepository.findByPolicyNumberAndTenantIdAndStatus(policyNumber, tenantId, "TERMINATED")).isPresent();
    }

    @Test
    void aFreeLookCancellationWaivesEveryUnsettledInvoiceFromInception() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "BILLING-FREELOOK-WAIVE-01");
        String policyNumber = issueDirectly(tenantId, fixture, new BigDecimal("15000.00"), "MONTHLY");

        publishCommitted(tenantId, "policy.PolicyCancelledFreeLook", Map.of("policyNumber", policyNumber,
            "cancelledAt", java.time.Instant.now().toString(), "cancelledBy", "staff-two"));

        assertThat(statusById(tenantId, policyNumber).values()).isNotEmpty().allMatch("WAIVED"::equals);
    }

    @Test
    void aLatePaymentAgainstAWaivedInvoicePublishesNoPremiumCollected() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "BILLING-COLLECTED-WAIVED-01");
        String policyNumber = issueDirectly(tenantId, fixture, new BigDecimal("15000.00"), "MONTHLY");
        InvoiceView invoice = billingApi.listInvoices(policyNumber, null).get(0);

        TenantContext.set(tenantId);
        billingApi.waiveInvoice(invoice.invoiceId(), "Contract test waiver before a late payment", "test-staff");
        // Negative control: genuinely WAIVED before the late payment, so this is not vacuous.
        assertThat(premiumInvoiceRepository.findByInvoiceIdAndTenantId(invoice.invoiceId(), tenantId)
            .orElseThrow().getStatus()).isEqualTo("WAIVED");

        eventRecorder.clear();
        billingApi.applyConfirmedPayment(invoice.invoiceId(), new BigDecimal("15000.00"), "TZS", "MM-WAIVED-LATE");

        // WAIVED is terminal and applyPayment writes nothing, so no premium was collected.
        assertThat(eventRecorder.ofType("billing.PremiumCollected")).isEmpty();
    }

    @Test
    void aPaymentAgainstAWaivedInvoiceLeavesItWaived() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "BILLING-PAYMENT-WAIVED-01");
        String policyNumber = issueDirectly(tenantId, fixture, new BigDecimal("15000.00"), "MONTHLY");
        InvoiceView invoice = billingApi.listInvoices(policyNumber, null).get(0);

        TenantContext.set(tenantId);
        billingApi.waiveInvoice(invoice.invoiceId(), "Contract test waiver before a late payment", "test-staff");

        // Negative control: the invoice is genuinely WAIVED before the late payment arrives, so
        // this test isn't vacuously passing against an invoice that was never in a non-WAIVED
        // state to begin with.
        PremiumInvoice beforePayment = premiumInvoiceRepository.findByInvoiceIdAndTenantId(invoice.invoiceId(), tenantId)
            .orElseThrow(() -> new AssertionError("expected the invoice to still exist"));
        assertThat(beforePayment.getStatus()).isEqualTo("WAIVED");

        billingApi.applyConfirmedPayment(invoice.invoiceId(), new BigDecimal("15000.00"), "TZS", "MM-LATE-01");

        PremiumInvoice afterPayment = premiumInvoiceRepository.findByInvoiceIdAndTenantId(invoice.invoiceId(), tenantId)
            .orElseThrow(() -> new AssertionError("expected the invoice to still exist"));
        assertThat(afterPayment.getStatus()).isEqualTo("WAIVED");
        // WAIVED is terminal -- a late payment must never quietly credit amountPaid either.
        assertThat(afterPayment.getAmountPaid()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * THE TRANSITION, THROUGH THE API THAT NOW REACHES IT.
     *
     * <p>{@link #reconcilingAFieldReceiptMovesItToReconciled} below calls {@code reconcile()} on
     * the aggregate directly, which is why it passed for as long as it did while the transition
     * was unreachable: {@code FieldReceipt.reconcile()} had ZERO callers anywhere in main source,
     * so a receipt could only ratchet PENDING_RECONCILIATION -> RECONCILIATION_OVERDUE and stay
     * there. That test proved the method worked, and nothing about whether anything could invoke
     * it. This one goes through {@code BillingApi}.
     */
    @Test
    void reconcilingThroughTheApiMovesTheReceiptAndRecordsWhoDidIt() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "BILLING-RECEIPT-API-01");
        String policyNumber = issueDirectly(tenantId, fixture, new BigDecimal("15000.00"), "MONTHLY");

        BillingApi.FieldReceiptResult result = billingApi.captureFieldReceipt(
            UUID.randomUUID(), policyNumber, new BigDecimal("15000.00"), "TZS",
            "client-key-" + UUID.randomUUID(), Instant.now());

        TenantContext.set(tenantId);
        Instant before = Instant.now();
        FieldReceiptView view = billingApi.reconcileFieldReceipt(result.receiptId(), "staff-finance-1");

        assertThat(view.status()).isEqualTo("RECONCILED");
        assertThat(view.reconciledAt()).isNotNull();

        // field_receipt has no actor column, so the event IS the record of who closed it --
        // the same shape waiveInvoice uses. Asserted through audit_log rather than by trusting
        // the publish, which is this file's established falsifiability idiom.
        List<AuditLogEntry> auditRows = auditLogRepository.findByTenantIdAndEventTypeAndOccurredAtBetween(
            tenantId, "billing.FieldReceiptReconciled", before.minusSeconds(5), Instant.now().plusSeconds(5));
        assertThat(auditRows).hasSize(1);
        JsonNode payload = objectMapper.readTree(auditRows.get(0).getPayload());
        assertThat(payload.path("receiptId").asText()).isEqualTo(result.receiptId().toString());
        assertThat(payload.path("reconciledBy").asText()).isEqualTo("staff-finance-1");
    }

    /** Two officers clearing the same queue row is a race, not an error -- and the second one
     *  must not publish a second event announcing a change that did not happen. */
    @Test
    void reconcilingAnAlreadyReconciledReceiptIsAQuietNoOp() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "BILLING-RECEIPT-API-02");
        String policyNumber = issueDirectly(tenantId, fixture, new BigDecimal("15000.00"), "MONTHLY");

        BillingApi.FieldReceiptResult result = billingApi.captureFieldReceipt(
            UUID.randomUUID(), policyNumber, new BigDecimal("15000.00"), "TZS",
            "client-key-" + UUID.randomUUID(), Instant.now());

        TenantContext.set(tenantId);
        Instant before = Instant.now();
        FieldReceiptView first = billingApi.reconcileFieldReceipt(result.receiptId(), "staff-finance-1");
        FieldReceiptView second = billingApi.reconcileFieldReceipt(result.receiptId(), "staff-finance-2");

        assertThat(second.status()).isEqualTo("RECONCILED");
        // The timestamp is the FIRST reconciliation's, not overwritten by the second caller.
        //
        // Truncated to milliseconds on both sides, and that is about storage rather than
        // laxity: the first call returns the in-memory Instant at nanosecond precision, while
        // the second returns the value round-tripped through a Postgres timestamptz, which
        // holds microseconds. Comparing them raw fails on digits Postgres cannot store, which
        // says nothing about whether the value was overwritten.
        assertThat(second.reconciledAt().truncatedTo(ChronoUnit.MILLIS))
            .isEqualTo(first.reconciledAt().truncatedTo(ChronoUnit.MILLIS));
        assertThat(auditLogRepository.findByTenantIdAndEventTypeAndOccurredAtBetween(
            tenantId, "billing.FieldReceiptReconciled", before.minusSeconds(5), Instant.now().plusSeconds(5)))
            .as("the no-op must not announce a second state change")
            .hasSize(1);
    }

    /** Past SLA is exactly when reconciling matters most, so RECONCILIATION_OVERDUE must not
     *  be a state that blocks it. */
    @Test
    void anOverdueReceiptIsStillReconcilable() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "BILLING-RECEIPT-API-03");
        String policyNumber = issueDirectly(tenantId, fixture, new BigDecimal("15000.00"), "MONTHLY");

        BillingApi.FieldReceiptResult result = billingApi.captureFieldReceipt(
            UUID.randomUUID(), policyNumber, new BigDecimal("15000.00"), "TZS",
            "client-key-" + UUID.randomUUID(), Instant.now());

        // Seeded exactly as sweep_billing_state()'s own SLA-breach UPDATE would perform it --
        // the same isolation idiom capturingAFieldReceiptThenSweepingAfterTheSlaWindow... uses,
        // so this test is about the reconcile transition and not about the sweep.
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(
                 "UPDATE billing.field_receipt SET status = 'RECONCILIATION_OVERDUE' WHERE receipt_id = ?")) {
            update.setObject(1, result.receiptId());
            assertThat(update.executeUpdate()).isEqualTo(1);
        }

        TenantContext.set(tenantId);
        assertThat(billingApi.reconcileFieldReceipt(result.receiptId(), "staff-finance-1").status())
            .isEqualTo("RECONCILED");
    }

    /** Tenant-scoped, and NOT FOUND rather than forbidden -- telling a caller "this exists but is
     *  not yours" confirms the id, which is the enumeration this platform closes everywhere. */
    @Test
    void aReceiptInAnotherTenantIsNotFound() {
        UUID ownerTenant = UUID.randomUUID();
        Fixture fixture = buildFixture(ownerTenant, "BILLING-RECEIPT-API-04");
        String policyNumber = issueDirectly(ownerTenant, fixture, new BigDecimal("15000.00"), "MONTHLY");

        BillingApi.FieldReceiptResult result = billingApi.captureFieldReceipt(
            UUID.randomUUID(), policyNumber, new BigDecimal("15000.00"), "TZS",
            "client-key-" + UUID.randomUUID(), Instant.now());

        TenantContext.set(UUID.randomUUID());
        assertThatThrownBy(() -> billingApi.reconcileFieldReceipt(result.receiptId(), "staff-finance-1"))
            .isInstanceOf(FieldReceiptNotFoundException.class);
    }

    /**
     * Exercises the AGGREGATE, not the platform. Kept because the domain should still state its
     * own rule and this is the only place it is testable with no API in the way -- but note it
     * passed happily for as long as {@code reconcile()} had no caller at all, so on its own it
     * proves nothing about reconciliation being reachable. See
     * {@link #reconcilingThroughTheApiMovesTheReceiptAndRecordsWhoDidIt}.
     */
    @Test
    void reconcilingAFieldReceiptMovesItToReconciled() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "BILLING-RECEIPT-RECONCILE-01");
        String policyNumber = issueDirectly(tenantId, fixture, new BigDecimal("15000.00"), "MONTHLY");

        BillingApi.FieldReceiptResult result = billingApi.captureFieldReceipt(
            UUID.randomUUID(), policyNumber, new BigDecimal("15000.00"), "TZS",
            "client-key-" + UUID.randomUUID(), Instant.now());
        assertThat(result.status()).isEqualTo("PENDING_RECONCILIATION");

        TenantContext.set(tenantId);
        FieldReceipt receipt = fieldReceiptRepository.findById(result.receiptId())
            .orElseThrow(() -> new AssertionError("expected a field_receipt row for " + result.receiptId()));
        // Negative control before the transition, same idiom as BillingSweepPsqlTest's own
        // pre-sweep checks.
        assertThat(receipt.getReconciledAt()).isNull();

        receipt.reconcile();
        fieldReceiptRepository.save(receipt);

        FieldReceipt reloaded = fieldReceiptRepository.findById(result.receiptId())
            .orElseThrow(() -> new AssertionError("expected a field_receipt row for " + result.receiptId()));
        assertThat(reloaded.getStatus()).isEqualTo("RECONCILED");
        assertThat(reloaded.getReconciledAt()).isNotNull();
    }
}
