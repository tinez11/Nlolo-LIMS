package tz.co.nlolo.lifeplatform.billing;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.audit.domain.AuditLogEntry;
import tz.co.nlolo.lifeplatform.audit.infrastructure.AuditLogRepository;
import tz.co.nlolo.lifeplatform.billing.api.BillingApi;
import tz.co.nlolo.lifeplatform.billing.api.InvoiceView;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = Application.class)
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
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/policy/V1__create_policy_schema.sql",
            "db-migrations/policy/V2__endorsement_append_only_and_money_checks.sql",
            "db-migrations/policy/V3__premium_fields.sql",
            "db-migrations/policy/V4__underwriting_case_id.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/refdata/V3__seed_billing_parameters.sql",
            "db-migrations/policyloan/V1__create_policyloan_schema.sql",
            "db-migrations/policyloan/V3__money_check_constraints.sql",
            "db-migrations/billing/V1__create_billing_schema.sql",
            "db-migrations/billing/V2__grants_rls_money_checks_and_notification_columns.sql",
            "db-migrations/billing/V3__amount_paid.sql",
            "db-migrations/payment/V1__create_payment_schema.sql",
            "db-migrations/payment/V2__grants_rls_money_checks_version_and_tenant_scoped_registries.sql",
            // M5 final-review fix wave: V3's callback resolvers and V4's widened status CHECK
            // (IN_DOUBT). This class drives real payment.PaymentRequestListener chains, so its
            // payment schema must match production's -- without V4 an indeterminate collection
            // outcome would fail the CHECK here while working in a real deployment.
            "db-migrations/payment/V3__inbound_callback_tenant_resolver.sql",
            "db-migrations/payment/V4__in_doubt_status_and_id_based_callback_resolvers.sql");
    }

    @AfterAll
    static void stopGateway() { wireMock.stop(); }

    @AfterEach
    void resetWireMock() { wireMock.resetAll(); }

    @Autowired private PolicyApi policyApi;
    @Autowired private BillingApi billingApi;
    @Autowired private tz.co.nlolo.lifeplatform.party.api.PartyApi partyApi;
    @Autowired private tz.co.nlolo.lifeplatform.product.api.ProductApi productApi;
    @Autowired private BillingScheduleRepository billingScheduleRepository;
    @Autowired private BillingApiImpl billingApiImpl;
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
                        tz.co.nlolo.lifeplatform.product.api.FactorType.AGE, "30-39", BigDecimal.ONE),
                    new tz.co.nlolo.lifeplatform.product.api.ProductApi.RatingFactorInput(
                        tz.co.nlolo.lifeplatform.product.api.FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new tz.co.nlolo.lifeplatform.product.api.ProductApi.BenefitInput(
                        tz.co.nlolo.lifeplatform.product.api.BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary");
        tz.co.nlolo.lifeplatform.product.api.ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        return new Fixture(applicant.partyId(), product.productId(), snapshot.productVersionId());
    }

    private String issueDirectly(UUID tenantId, Fixture fixture, BigDecimal premiumAmount, String premiumFrequency) {
        TenantContext.set(tenantId);
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(fixture.applicantId(), fixture.productId(), fixture.productVersionId(),
            new BigDecimal("1000000"), "TZS", premiumAmount, "TZS", premiumFrequency, null, List.of(), "Direct issuance test");
        return policyApi.issuePolicy(UUID.randomUUID(), request, "test-staff").policyNumber();
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
        billingApiImpl.publishPendingNotifications(tenantId);

        assertThat(policyApi.getPolicy(policyNumber).status()).isEqualTo(PolicyStatus.LAPSED);
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
        // publishPendingNotifications as the one thing under test.
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(
                 "UPDATE billing.field_receipt SET status = 'RECONCILIATION_OVERDUE' WHERE receipt_id = ?")) {
            update.setObject(1, result.receiptId());
            assertThat(update.executeUpdate()).isEqualTo(1);
        }

        TenantContext.set(tenantId);
        Instant before = Instant.now();
        int published = billingApiImpl.publishPendingNotifications(tenantId);
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
        assertThat(billingApiImpl.publishPendingNotifications(tenantId)).isEqualTo(0);
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
