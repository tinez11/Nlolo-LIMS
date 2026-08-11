package tz.co.nlolo.lifeplatform.billing;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.billing.api.BillingApi;
import tz.co.nlolo.lifeplatform.billing.api.InvoiceView;
import tz.co.nlolo.lifeplatform.billing.application.BillingApiImpl;
import tz.co.nlolo.lifeplatform.billing.domain.BillingSchedule;
import tz.co.nlolo.lifeplatform.billing.infrastructure.BillingScheduleRepository;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyStatus;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = Application.class)
@Testcontainers
class BillingApiIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

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
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/policy/V1__create_policy_schema.sql",
            "db-migrations/policy/V2__endorsement_append_only_and_money_checks.sql",
            "db-migrations/policy/V3__premium_fields.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/refdata/V3__seed_billing_parameters.sql",
            "db-migrations/policyloan/V1__create_policyloan_schema.sql",
            "db-migrations/policyloan/V3__money_check_constraints.sql",
            "db-migrations/billing/V1__create_billing_schema.sql",
            "db-migrations/billing/V2__grants_rls_money_checks_and_notification_columns.sql");
    }

    @Autowired private PolicyApi policyApi;
    @Autowired private BillingApi billingApi;
    @Autowired private tz.co.nlolo.lifeplatform.party.api.PartyApi partyApi;
    @Autowired private tz.co.nlolo.lifeplatform.product.api.ProductApi productApi;
    @Autowired private BillingScheduleRepository billingScheduleRepository;
    @Autowired private BillingApiImpl billingApiImpl;
    @Autowired private DataSource dataSource;

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
}
