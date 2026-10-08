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
import tz.co.nlolo.lifeplatform.policyloan.api.PolicyLoanApi;
import java.util.Map;
import tz.co.nlolo.lifeplatform.product.api.AccumulationChargeRow;
import tz.co.nlolo.lifeplatform.product.api.AccumulationPlan;
import tz.co.nlolo.lifeplatform.product.api.ValueBasis;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Partial withdrawals through the real payment rail (an in-process WireMock gateway): two people,
 * valued at approval, refused below the minimum balance net of a loan lien, and reversed -- never
 * edited -- when the payment fails. Also a person's two-person adjustment.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(AccumulationTestFixtures.class)
class WithdrawalIntegrationTest {

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
            "db-migrations/product/V27__ifrs17_classification.sql",
            "db-migrations/product/V28__survival_investment_component.sql",
            "db-migrations/product/V29__funeral_group_rate.sql",
            "db-migrations/product/V30__funeral_group_rate_period.sql",
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
            "db-migrations/underwriting/V18__sale_channel_and_branch.sql",
            "db-migrations/underwriting/V19__group_funeral_proposal.sql",
            "db-migrations/underwriting/V20__sale_lock_backfill.sql",
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
            "db-migrations/policyloan/V8__interest_month_published.sql");
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

    private <T> T asTenant(java.util.function.Supplier<T> work) {
        TenantContext.set(TENANT);
        try { return work.get(); } finally { TenantContext.clear(); }
    }

    /** 200,000 in, 5% allocation: 190,000 in the account. Minimum balance 50,000. */
    private String funded() {
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now());
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("200000.00"), LocalDate.now());
        return issued.policyNumber();
    }

    @Test
    void aWithdrawalNeedsTwoPeopleAndLeavesTheAccountOnApproval() {
        String policy = funded();
        var requested = asTenant(() -> api.requestWithdrawal(policy, new BigDecimal("40000.00"), "+255700000001", "staff-one"));
        assertThat(asTenant(() -> api.findAccount(policy)).orElseThrow().balance()).isEqualByComparingTo("190000.00");

        assertThatThrownBy(() -> asTenant(() -> api.approveWithdrawal(requested.withdrawalId(), "staff-one")))
            .isInstanceOf(AccumulationStateException.class)
            .hasMessage("A withdrawal must be approved by someone other than the person who requested it");

        asTenant(() -> api.approveWithdrawal(requested.withdrawalId(), "finance-two"));
        assertThat(asTenant(() -> api.findAccount(policy)).orElseThrow().balance()).isEqualByComparingTo("150000.00");
        assertThat(asTenant(() -> api.entries(policy))).last()
            .satisfies(e -> {
                assertThat(e.type()).isEqualTo(EntryType.WITHDRAWAL);
                assertThat(e.approvedBy()).isEqualTo("finance-two");
            });
    }

    @Test
    void aWithdrawalBelowTheMinimumBalanceIsRefusedByName() {
        String policy = funded();
        assertThatThrownBy(() -> asTenant(() -> api.requestWithdrawal(policy, new BigDecimal("140000.01"), "+255700000001", "staff-one")))
            .isInstanceOf(AccumulationStateException.class)
            .hasMessage("A withdrawal of 140000.01 would leave 49999.99, below this product's minimum balance of 50000.00. "
                + "The most that can be withdrawn is 140000.00.");
        assertThat(asTenant(() -> api.requestWithdrawal(policy, new BigDecimal("140000.00"), "+255700000001", "staff-one")))
            .isNotNull();
    }

    @Test
    void aLoanLienReducesWhatCanBeWithdrawn() {
        String policy = funded();
        // Through the real policyloan path, not a hand-set encumbrance: copy how
        // PolicyLoanIntegrationTest originates a loan, for 30,000 against this policy.
        originateLoan(policy, new BigDecimal("30000.00"));
        assertThatThrownBy(() -> asTenant(() -> api.requestWithdrawal(policy, new BigDecimal("110000.01"), "+255700000001", "staff-one")))
            .isInstanceOf(AccumulationStateException.class)
            .hasMessageContaining("The most that can be withdrawn is 110000.00");
    }

    @Test
    void aFailedDisbursementIsReversedNotEdited() {
        String policy = funded();
        forceTheGatewayToDecline();
        var requested = asTenant(() -> api.requestWithdrawal(policy, new BigDecimal("40000.00"), "+255700000001", "staff-one"));
        asTenant(() -> api.approveWithdrawal(requested.withdrawalId(), "finance-two"));

        List<LedgerEntryView> entries = asTenant(() -> api.entries(policy));
        LedgerEntryView withdrawal = entries.stream().filter(e -> e.type() == EntryType.WITHDRAWAL).findFirst().orElseThrow();
        LedgerEntryView reversal = entries.get(entries.size() - 1);
        assertThat(reversal.type()).isEqualTo(EntryType.REVERSAL);
        assertThat(reversal.reversesEntryId()).isEqualTo(withdrawal.entryId());
        assertThat(reversal.amount()).isEqualByComparingTo("40000.00");
        // The original is still there, unchanged.
        assertThat(withdrawal.amount()).isEqualByComparingTo("-40000.00");
        assertThat(asTenant(() -> api.findAccount(policy)).orElseThrow().balance()).isEqualByComparingTo("190000.00");
        assertThat(asTenant(() -> api.listWithdrawals(policy))).first()
            .extracting(WithdrawalView::status).isEqualTo("FAILED");
    }

    @Test
    void aSecondWithdrawalWhileOneIsInFlightIsRefused() {
        String policy = funded();
        asTenant(() -> api.requestWithdrawal(policy, new BigDecimal("10000.00"), "+255700000001", "staff-one"));
        assertThatThrownBy(() -> asTenant(() -> api.requestWithdrawal(policy, new BigDecimal("10000.00"), "+255700000001", "staff-one")))
            .isInstanceOf(AccumulationStateException.class)
            .hasMessage("A withdrawal is already in flight on policy " + policy);
    }

    @Test
    void aSuccessfulDisbursementMarksTheWithdrawalPaid() {
        String policy = funded();
        var requested = asTenant(() -> api.requestWithdrawal(policy, new BigDecimal("40000.00"), "+255700000001", "staff-one"));
        asTenant(() -> api.approveWithdrawal(requested.withdrawalId(), "finance-two"));
        assertThat(asTenant(() -> api.listWithdrawals(policy))).first()
            .extracting(WithdrawalView::status).isEqualTo("PAID");
    }

    @Autowired private PolicyLoanApi policyLoanApi;

    /**
     * A REAL loan, through policyloan: origination reserves loan value and confirms it in the same
     * call, which is what puts the lien on the policy account. Never a hand-set encumbrance -- that
     * would prove this module reads a column without proving anything ever writes it.
     */
    private void originateLoan(String policyNumber, BigDecimal amount) {
        asTenant(() -> policyLoanApi.originateLoan(policyNumber, amount, "TZS", "MPESA-0712345678", "test-agent"));
    }

    @Test
    void anAdjustmentNeedsASecondPersonAndPostsOnlyOnApproval() {
        String policy = funded();
        var proposed = asTenant(() -> api.proposeAdjustment(policy, new BigDecimal("-250.00"), "Fee charged twice in error", "staff-one"));
        assertThat(asTenant(() -> api.entries(policy))).noneMatch(e -> e.type() == EntryType.ADJUSTMENT);
        assertThatThrownBy(() -> asTenant(() -> api.approveAdjustment(proposed.adjustmentId(), "staff-one")))
            .isInstanceOf(AccumulationStateException.class)
            .hasMessage("An adjustment must be decided by someone other than the person who proposed it");
        asTenant(() -> api.approveAdjustment(proposed.adjustmentId(), "finance-two"));
        assertThat(asTenant(() -> api.entries(policy))).last().satisfies(e -> {
            assertThat(e.type()).isEqualTo(EntryType.ADJUSTMENT);
            assertThat(e.createdBy()).isEqualTo("staff-one");
            assertThat(e.approvedBy()).isEqualTo("finance-two");
        });
    }

    @Test
    void aRejectedAdjustmentPostsNothing() {
        String policy = funded();
        var proposed = asTenant(() -> api.proposeAdjustment(policy, new BigDecimal("500.00"), "Goodwill", "staff-one"));
        asTenant(() -> api.rejectAdjustment(proposed.adjustmentId(), "finance-two"));
        assertThat(asTenant(() -> api.entries(policy))).noneMatch(e -> e.type() == EntryType.ADJUSTMENT);
    }
}
