package tz.co.nlolo.lifeplatform.benefitpayout;

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
import tz.co.nlolo.lifeplatform.benefitpayout.api.BenefitPayoutApi;
import tz.co.nlolo.lifeplatform.benefitpayout.api.InstalmentStatus;
import tz.co.nlolo.lifeplatform.benefitpayout.api.PayoutInstalmentView;
import tz.co.nlolo.lifeplatform.benefitpayout.application.PayoutDueDrain;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.JournalEntryView;
import tz.co.nlolo.lifeplatform.product.api.*;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real proof that the payout loop is closed: approve over the real API -> the real
 * {@code PayoutRequested} event -> payment's real listener -> the mobile-money gateway (an
 * in-process WireMock stand-in, the same shape {@code LoanDisbursementEndToEndTest} uses) ->
 * {@code DisbursementCompleted} -> the instalment reaches PAID -> finaccounting books it.
 *
 * <p>No test here calls {@code markPaid} or {@code markFailed} directly. The whole AFTER_COMMIT
 * chain is synchronous and same-thread, so it has completed by the time {@code approve} returns --
 * no sleeps or awaits anywhere in this class.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(PayoutTestFixtures.class)
class PayoutPaymentEndToEndTest {

    private static final UUID TENANT = UUID.randomUUID();

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static WireMockServer wireMock;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

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
            "db-migrations/party/V7__kyc_decided_by_name.sql", "db-migrations/party/V8__preferred_language.sql",
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
            "db-migrations/product/V33__term_account_rate_band.sql",
            "db-migrations/product/V34__version_account_charges.sql",
            "db-migrations/product/V35__product_description.sql",
            "db-migrations/benefitpayout/V1__create_benefitpayout_schema.sql",
            "db-migrations/benefitpayout/V2__annuity_streams.sql",
            "db-migrations/benefitpayout/V3__withholding.sql",
            "db-migrations/benefitpayout/V4__withholding_rule_end.sql", "db-migrations/benefitpayout/V6__acted_alone.sql",
            "db-migrations/accumulation/V1__create_accumulation_schema.sql",
            "db-migrations/accumulation/V6__withdrawal_payout_route.sql",
            "db-migrations/accumulation/V9__withdrawal_rejection.sql", "db-migrations/accumulation/V10__acted_alone.sql",
            "db-migrations/accumulation/V7__top_up_methods.sql",
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
            "db-migrations/underwriting/V24__term_account_rate.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/refdata/V8__ifrs17_branches_and_channels.sql",
            "db-migrations/refdata/V9__journal_reason_codes.sql",
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
            "db-migrations/policy/V42__surrender_payout_route.sql", "db-migrations/policy/V45__acted_alone.sql",
            // A maturity repays the policy's loans first (2026-11-07, E7): the loans this test takes need policyloan.
            "db-migrations/policyloan/V1__create_policyloan_schema.sql",
            "db-migrations/policyloan/V3__money_check_constraints.sql",
            "db-migrations/policyloan/V4__persist_reservation_id.sql",
            "db-migrations/policyloan/V5__loan_interest_accrual.sql",
            "db-migrations/policyloan/V7__q4_2026_partitions.sql",
            "db-migrations/policyloan/V8__interest_month_published.sql",
            "db-migrations/policyloan/V9__payout_approval.sql",
            "db-migrations/payment/V22__loan_repayment_purpose.sql",
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql",
            "db-migrations/payment/V1__create_payment_schema.sql",
            "db-migrations/payment/V16__payment_policy_number.sql",
            "db-migrations/payment/V2__grants_rls_money_checks_version_and_tenant_scoped_registries.sql",
            "db-migrations/payment/V6__disbursement_method.sql",
            "db-migrations/payment/V7__q4_2026_partitions.sql",
            "db-migrations/payment/V8__benefit_payout_purposes.sql",
            "db-migrations/payment/V9__account_purposes.sql",
            "db-migrations/payment/V17__manual_receipts.sql",
            "db-migrations/payment/V18__payout_routes.sql",
            "db-migrations/payment/V19__top_up_one_person.sql", "db-migrations/payment/V20__premium_refund_purpose.sql", "db-migrations/payment/V21__acted_alone.sql",
            "db-migrations/payment/V23__balance_withdrawal_purpose.sql",
            "db-migrations/payment/V24__reversed_status.sql",
            "db-migrations/payment/V25__deposit_purpose.sql", "db-migrations/payment/V26__receipt_number.sql", "db-migrations/payment/V27__reinsurance_settlement_purpose.sql",
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
            "db-migrations/finaccounting/V18__policy_snapshot_lives.sql", "db-migrations/finaccounting/V19__acted_alone.sql", "db-migrations/finaccounting/V20__dismissal_needs_a_second_person.sql", "db-migrations/finaccounting/V21__baseline_sign_off.sql");
    }

    @AfterAll
    static void stopGateway() {
        wireMock.stop();
    }

    @BeforeEach
    void resetGateway() {
        wireMock.resetAll();
    }

    @Autowired private BenefitPayoutApi api;
    @Autowired private PayoutDueDrain drain;
    @Autowired private PayoutTestFixtures fixtures;
    @Autowired private FinaccountingApi finaccountingApi;

    private static final PayoutPlan MATURING_ENDOWMENT = PayoutPlan.authored(
        new PayoutTerms(15, null, null, null),
        List.of(new PayoutRowInput(PayoutKind.MATURITY, null, null,
            PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("100"), null)));

    private void gatewayAccepts(String reference) {
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"ACCEPTED\",\"gatewayReference\":\"" + reference + "\"}")));
    }

    private void gatewayRejects() {
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"REJECTED\",\"reason\":\"INSUFFICIENT_FLOAT\"}")));
    }

    /** A twelve-month endowment whose maturity date was yesterday, with its premiums paid. */
    private String maturityDueYesterday() {
        String policyNumber = fixtures.issueEndowment(TENANT, MATURING_ENDOWMENT, new BigDecimal("500000.00"), 12,
            LocalDate.now().minusYears(1).minusDays(1));
        fixtures.collectPremium(TENANT, policyNumber, new BigDecimal("600000.00"), LocalDate.now().minusDays(1));
        return policyNumber;
    }

    private PayoutInstalmentView only(String policyNumber) {
        TenantContext.set(TENANT);
        try {
            return api.listForPolicy(policyNumber).get(0);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void anApprovedMaturityIsPaidAndPostedToTheLedger() {
        String policyNumber = maturityDueYesterday();
        drain.drain();
        UUID id = only(policyNumber).instalmentId();
        gatewayAccepts("MM-PAYOUT-OK");

        TenantContext.set(TENANT);
        try {
            api.review(id, "+255700000009", null, null, "rev-1");
            // The whole chain completes inside this call.
            api.approve(id, "apr-2");
            assertThat(api.getInstalment(id).status()).isEqualTo(InstalmentStatus.PAID);
        } finally {
            TenantContext.clear();
        }

        wireMock.verify(exactly(1), postRequestedFor(urlPathEqualTo("/disburse")));

        TenantContext.set(TENANT);
        try {
            // Booked against the ledger, keyed by the instalment -- the half that was missing for
            // surrender until step 1's gap fix, and must not go missing again here. Since IFRS 17 I3b in two
            // journals, as the guide does it: payable when it fell due (C-01), cleared when paid (C-03).
            var entries = finaccountingApi.listJournalEntries(null, policyNumber, Pageable.unpaged()).getContent();
            assertThat(entries).extracting(JournalEntryView::sourceRef).containsOnly(id.toString());
            assertThat(entries).extracting(JournalEntryView::sourceEvent)
                .containsExactlyInAnyOrder("benefitpayout.PayoutRequested", "benefitpayout.PayoutPaid");
        } finally {
            TenantContext.clear();
        }
    }

    @Autowired private tz.co.nlolo.lifeplatform.policyloan.api.PolicyLoanApi policyLoanApi;

    /** One row changed over the container's own credentials, past the app role's RLS -- a fixture's direct seed. */
    private static void superuser(String sql, String policyNumber) throws Exception {
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                 POSTGRES.getUsername(), POSTGRES.getPassword());
             java.sql.PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, policyNumber);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    /**
     * 2026-11-07, decision E7: a maturity repays the policy's loan first. The rail is asked for the rest; the maturity
     * payable 2212 is cleared by the loan's part (E-03-MATURITY, no cash) and by what was paid (C-03).
     */
    @Test
    void aMaturityRepaysThePolicyLoanFirstAndOnlyTheRestIsPaid() throws Exception {
        // Maturing tomorrow: in force today, so the loan can be taken; its maturity payout is then brought forward
        // to yesterday, so it falls due now.
        String policyNumber = fixtures.issueEndowment(TENANT, MATURING_ENDOWMENT, new BigDecimal("500000.00"), 12,
            LocalDate.now().minusYears(1).plusDays(1));
        fixtures.collectPremium(TENANT, policyNumber, new BigDecimal("600000.00"), LocalDate.now().minusDays(1));
        superuser("UPDATE policy.policy_account SET cash_value_amount = 400000 WHERE policy_number = ?", policyNumber);
        gatewayAccepts("MM-LOAN-AND-MATURITY");
        TenantContext.set(TENANT);
        UUID loanId;
        try {
            loanId = policyLoanApi.originateLoan(policyNumber, new BigDecimal("300000"), "TZS", "+255700000011", "agent")
                .loanId();
            policyLoanApi.approvePayout(loanId, "approver");
            assertThat(policyLoanApi.getLoan(loanId).status())
                .isEqualTo(tz.co.nlolo.lifeplatform.policyloan.api.LoanStatus.DISBURSED);
        } finally {
            TenantContext.clear();
        }
        superuser("UPDATE benefitpayout.payout_instalment SET due_date = current_date - 1 WHERE policy_number = ?", policyNumber);
        wireMock.resetRequests();

        drain.drain();
        UUID id = only(policyNumber).instalmentId();
        TenantContext.set(TENANT);
        try {
            api.review(id, "+255700000009", null, null, "rev-1");
            api.approve(id, "apr-2");
            assertThat(api.getInstalment(id).status()).isEqualTo(InstalmentStatus.PAID);
            assertThat(policyLoanApi.getLoan(loanId).status()).as("the maturity repaid the loan")
                .isEqualTo(tz.co.nlolo.lifeplatform.policyloan.api.LoanStatus.SETTLED);
        } finally {
            TenantContext.clear();
        }
        // The 500,000 maturity less the 300,000 the loan owed.
        wireMock.verify(exactly(1), postRequestedFor(urlPathEqualTo("/disburse")).withRequestBody(containing("200000")));

        TenantContext.set(TENANT);
        try {
            var postings = finaccountingApi.listJournalEntries(null, policyNumber, Pageable.unpaged()).getContent().stream()
                .flatMap(e -> e.postings().stream()).toList();
            java.util.function.Function<String, BigDecimal> net = account -> postings.stream()
                .filter(p -> account.equals(p.accountCode()))
                .map(p -> p.direction() == tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection.DR
                    ? p.amount() : p.amount().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(net.apply("2212")).as("the maturity payable is cleared exactly").isEqualByComparingTo("0");
            // The loan's payout (E-01) is posted with no policy, so this policy's journals show only its repayment: the
            // receivable falls by exactly what the loan owed, out of the maturity, with no cash.
            assertThat(net.apply("2125")).as("the loan receivable falls by what the loan owed").isEqualByComparingTo("-300000");
            assertThat(postings).filteredOn(p -> "policyloan.LoanRepaid".equals(p.sourceEvent()))
                .extracting(p -> p.accountCode()).doesNotContain("1120", "1140");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void aFailedPayoutRetriesWithAFreshRequest() {
        String policyNumber = maturityDueYesterday();
        drain.drain();
        UUID id = only(policyNumber).instalmentId();
        gatewayRejects();

        TenantContext.set(TENANT);
        try {
            api.review(id, "+255700000009", null, null, "rev-1");
            api.approve(id, "apr-2");
            assertThat(api.getInstalment(id).status()).isEqualTo(InstalmentStatus.FAILED);

            // The money did not move, so it can be tried again -- under a NEW idempotency key,
            // which is why the gateway sees a second call rather than a dropped duplicate.
            gatewayAccepts("MM-PAYOUT-RETRY");
            assertThat(api.retry(id).attempts()).isEqualTo(2);
            assertThat(api.getInstalment(id).status()).isEqualTo(InstalmentStatus.PAID);
        } finally {
            TenantContext.clear();
        }

        wireMock.verify(exactly(2), postRequestedFor(urlPathEqualTo("/disburse")));
    }
}
