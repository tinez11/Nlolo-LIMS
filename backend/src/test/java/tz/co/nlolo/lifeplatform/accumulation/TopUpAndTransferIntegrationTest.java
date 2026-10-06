package tz.co.nlolo.lifeplatform.accumulation;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.accumulation.application.AccumulationApiImpl;
import tz.co.nlolo.lifeplatform.product.api.AccumulationChargeRow;
import tz.co.nlolo.lifeplatform.product.api.ValueBasis;
import tz.co.nlolo.lifeplatform.product.api.AccumulationChargeRow;
import tz.co.nlolo.lifeplatform.product.api.AccumulationPlan;
import tz.co.nlolo.lifeplatform.product.api.ValueBasis;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Money in that is not a premium: a top-up collected through the real payment rail and credited at the
 * contribution rate -- which billing must leave alone -- and a transfer in at the transfer rate.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(AccumulationTestFixtures.class)
class TopUpAndTransferIntegrationTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    static WireMockServer wireMock;

    @DynamicPropertySource
    static void mobileMoneyProperties(DynamicPropertyRegistry registry) {
        registry.add("mobile-money.base-url", () -> wireMock.baseUrl());
    }

    @BeforeAll
    static void startGatewayAndApplyMigrations() throws Exception {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
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
            "db-migrations/product/V21__bonus_terms.sql",
            "db-migrations/accumulation/V1__create_accumulation_schema.sql",
            "db-migrations/benefitpayout/V1__create_benefitpayout_schema.sql",
            "db-migrations/benefitpayout/V2__annuity_streams.sql",
            "db-migrations/benefitpayout/V3__withholding.sql",
            "db-migrations/benefitpayout/V4__withholding_rule_end.sql",
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
            "db-migrations/refdata/V8__ifrs17_branches_and_channels.sql",
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
            "db-migrations/audit/V3__q4_2026_partitions.sql",
            "db-migrations/payment/V1__create_payment_schema.sql",
            "db-migrations/payment/V2__grants_rls_money_checks_version_and_tenant_scoped_registries.sql",
            "db-migrations/payment/V6__disbursement_method.sql",
            "db-migrations/payment/V7__q4_2026_partitions.sql",
            "db-migrations/payment/V8__benefit_payout_purposes.sql",
            "db-migrations/payment/V9__account_purposes.sql",
            "db-migrations/policyloan/V1__create_policyloan_schema.sql",
            "db-migrations/policyloan/V3__money_check_constraints.sql",
            "db-migrations/policyloan/V4__persist_reservation_id.sql",
            "db-migrations/policyloan/V5__loan_interest_accrual.sql",
            "db-migrations/policyloan/V7__q4_2026_partitions.sql",
            "db-migrations/billing/V1__create_billing_schema.sql",
            "db-migrations/billing/V2__grants_rls_money_checks_and_notification_columns.sql",
            "db-migrations/billing/V3__amount_paid.sql",
            "db-migrations/billing/V5__single_premium_invoice.sql",
            "db-migrations/billing/V6__premium_credit.sql",
            "db-migrations/billing/V7__policy_inception_invoice.sql",
            "db-migrations/billing/V8__schedule_premium_paying_until.sql",
            "db-migrations/billing/V9__schedules_due_for_invoicing.sql");
    }

    @AfterAll
    static void stopGateway() {
        wireMock.stop();
    }

    /** Every test starts with a gateway that accepts both disbursements and collections. */
    @BeforeEach
    void gatewayAccepts() {
        wireMock.resetAll();
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"ACCEPTED\",\"gatewayReference\":\"GW-OK\"}")));
        wireMock.stubFor(post(urlPathEqualTo("/collect")).willReturn(okJson(
            "{\"status\":\"ACCEPTED\",\"gatewayReference\":\"GW-OK\"}")));
    }

    private void forceTheGatewayToDecline() {
        wireMock.resetAll();
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"REJECTED\",\"reason\":\"INSUFFICIENT_FLOAT\"}")));
    }

    private static final UUID TENANT = UUID.randomUUID();
    @Autowired private AccumulationTestFixtures fixtures;
    @Autowired private AccumulationApi api;
    @Autowired private AccumulationApiImpl impl;

    private <T> T asTenant(java.util.function.Supplier<T> work) {
        TenantContext.set(TENANT);
        try { return work.get(); } finally { TenantContext.clear(); }
    }

    @Test
    void aTopUpIsCollectedThroughPaymentAndCreditedAtTheContributionRate() {
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now());
        var topUp = asTenant(() -> api.requestTopUp(issued.policyNumber(), new BigDecimal("20000.00"), "+255700000002", "staff-one"));
        // The mock rail confirms synchronously; the confirmation reaches accumulation, not billing.
        assertThat(asTenant(() -> api.entries(issued.policyNumber())))
            .extracting(LedgerEntryView::type, e -> e.amount().toPlainString())
            .containsExactly(tuple(EntryType.TOP_UP, "20000.00"), tuple(EntryType.ALLOCATION_CHARGE, "-1000.00"));
        assertThat(asTenant(() -> api.listTopUps(issued.policyNumber()))).first()
            .extracting(TopUpView::status).isEqualTo("COLLECTED");
        assertThat(asTenant(() -> api.entries(issued.policyNumber()))).allSatisfy(e ->
            assertThat(e.sourceRef()).isEqualTo("topup:" + topUp.topUpId()));
    }

    @Test
    void aTopUpConfirmationIsNotTreatedAsAnInvoicePayment() {
        // Before payment V9, billing parsed EVERY confirmed sourceRef as an invoice id. Asserted the
        // decisive way: a confirmation marked ACCOUNT_TOP_UP whose sourceRef IS a real, unpaid invoice.
        // Without billing's guard that invoice would be paid; with it, billing leaves it alone.
        // (A real top-up's sourceRef is a top-up id, which matches no invoice -- so asserting on one
        // would pass with or without the guard and prove nothing.)
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now());
        String invoiceId = firstInvoiceOf(issued.policyNumber());
        fixtures.publish(TENANT, "payment.PaymentConfirmed", confirmation(invoiceId, "ACCOUNT_TOP_UP"));
        assertThat(billingInvoicesPaidFor(issued.policyNumber())).isZero();
    }

    @Test
    void aPremiumConfirmationStillPaysItsInvoice() {
        // The control for the test above: the same confirmation marked PREMIUM -- and one with no
        // purpose at all, as every confirmation before payment V9 -- does pay.
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now());
        String invoiceId = firstInvoiceOf(issued.policyNumber());
        fixtures.publish(TENANT, "payment.PaymentConfirmed", confirmation(invoiceId, "PREMIUM"));
        assertThat(billingInvoicesPaidFor(issued.policyNumber())).isEqualTo(1);
    }

    private Map<String, Object> confirmation(String invoiceId, String purpose) {
        return Map.of("paymentRequestId", UUID.randomUUID(), "idempotencyKey", "test:" + UUID.randomUUID(),
            "sourceRef", invoiceId, "gatewayReference", "GW-TEST",
            "amount", Map.of("amount", invoiceAmount(invoiceId), "currencyCode", "TZS"),
            "confirmedAt", java.time.Instant.now().toString(), "purpose", purpose);
    }

    private String firstInvoiceOf(String policyNumber) {
        return ownerQuery("SELECT invoice_id::text FROM billing.premium_invoice WHERE policy_number = ? ORDER BY due_date LIMIT 1",
            policyNumber);
    }

    private String invoiceAmount(String invoiceId) {
        return ownerQuery("SELECT amount::text FROM billing.premium_invoice WHERE invoice_id = ?::uuid", invoiceId);
    }

    private String ownerQuery(String sql, String arg) {
        try (var c = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var st = c.prepareStatement(sql)) {
            st.setString(1, arg);
            var rs = st.executeQuery();
            if (!rs.next()) {
                throw new AssertionError("expected a billing invoice for " + arg + " -- issuance should raise one");
            }
            return rs.getString(1);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aTransferInIsCreditedAtTheTransferRate() {
        var plan = new AccumulationPlan(ValueBasis.ACCOUNT, new BigDecimal("3"), new BigDecimal("50000.00"),
            List.of(new AccumulationChargeRow(1, null, new BigDecimal("5"), new BigDecimal("2"), new BigDecimal("1000.00"))));
        var issued = fixtures.issueSavingsPlan(TENANT, plan, LocalDate.now());
        asTenant(() -> api.recordTransferIn(issued.policyNumber(), new BigDecimal("300000.00"), "NSSF member 1234",
            "DOC-1", "staff-one"));
        assertThat(asTenant(() -> api.entries(issued.policyNumber())))
            .extracting(LedgerEntryView::type, e -> e.amount().toPlainString())
            .containsExactly(tuple(EntryType.TRANSFER_IN, "300000.00"), tuple(EntryType.ALLOCATION_CHARGE, "-6000.00"));
    }

    @Test
    void aTopUpOnAClosedAccountIsRefused() {
        LocalDate start = LocalDate.now().withDayOfMonth(1).minusMonths(3);
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, start);
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("1500.00"), start);
        asTenant(() -> { impl.postMonthEnds(issued.policyNumber(), LocalDate.now()); return null; });
        assertThatThrownBy(() -> asTenant(() -> api.requestTopUp(issued.policyNumber(), new BigDecimal("1.00"), "+255700000002", "s")))
            .isInstanceOf(AccumulationStateException.class)
            .hasMessageContaining("account is closed");
    }

    /** PAID invoices billing holds for the policy, read over the owner connection (RLS does not apply). */
    private int billingInvoicesPaidFor(String policyNumber) {
        try (var c = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var st = c.prepareStatement("SELECT count(*) FROM billing.premium_invoice WHERE policy_number = ? AND status = 'PAID'")) {
            st.setString(1, policyNumber);
            var rs = st.executeQuery();
            rs.next();
            return rs.getInt(1);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
