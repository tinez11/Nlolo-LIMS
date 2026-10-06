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
import tz.co.nlolo.lifeplatform.policy.api.PolicyStatus;
import tz.co.nlolo.lifeplatform.benefitpayout.api.BenefitPayoutApi;
import tz.co.nlolo.lifeplatform.product.api.PayoutKind;
import tz.co.nlolo.lifeplatform.benefitpayout.application.PayoutDueDrain;
import tz.co.nlolo.lifeplatform.accumulation.application.AccumulationApiImpl;
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
 * Closing an account, end to end: a surrender valued at approval and paid through the real rail, a
 * maturity paying the whole account on its date, a death reversing what came after it, a free-look
 * emptying the account -- each as one posting -- and the death ceiling agreeing with the account.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(AccumulationTestFixtures.class)
class ClosingIntegrationTest {

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
            "db-migrations/policy/V37__sale_classification.sql",
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
            "db-migrations/policyloan/V7__q4_2026_partitions.sql");
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
    @Autowired private AccumulationApiImpl api;
    @Autowired private PolicyApi policyApi;
    @Autowired private BenefitPayoutApi benefitPayoutApi;
    @Autowired private PayoutDueDrain dueDrain;

    private <T> T asTenant(java.util.function.Supplier<T> work) {
        TenantContext.set(TENANT);
        try { return work.get(); } finally { TenantContext.clear(); }
    }
    private List<LedgerEntryView> entriesOf(String p) { return asTenant(() -> api.entries(p)); }

    /** 100,000 in on the 1st of the month two months ago, both month-ends posted. */
    private String fundedWithMonthEnds() {
        LocalDate start = LocalDate.now().withDayOfMonth(1).minusMonths(2);
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, start);
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("100000.00"), start);
        asTenant(() -> { api.postMonthEnds(issued.policyNumber(), LocalDate.now()); return null; });
        return issued.policyNumber();
    }

    @Test
    void aSurrenderClosesTheAccountWithInterestToTheDayAndPaysItLessTheCharge() {
        String policy = fundedWithMonthEnds();
        ClosingQuote quote = asTenant(() -> api.quoteClosing(policy, LocalDate.now()));
        var request = asTenant(() -> policyApi.requestSurrender(policy, "+255700000003", "staff-one"));
        asTenant(() -> policyApi.approveSurrender(request.surrenderRequestId(), "finance-two"));

        List<LedgerEntryView> entries = entriesOf(policy);
        LedgerEntryView closing = entries.get(entries.size() - 1);
        assertThat(closing.type()).isEqualTo(EntryType.SURRENDER);
        assertThat(closing.balanceAfter()).isEqualByComparingTo("0.00");
        // The figure the approver was shown is the figure that moved.
        assertThat(closing.amount().negate()).isEqualByComparingTo(quote.value());
        assertThat(asTenant(() -> api.findAccount(policy)).orElseThrow().closedReason()).isEqualTo("SURRENDERED");
        // The fixture version authors no surrender charge, so the whole value is paid -- and the
        // request is marked PAID through step 1's own listener, by the source ref.
        assertThat(asTenant(() -> policyApi.findLatestSurrenderRequest(policy).orElseThrow()).status()).isEqualTo("PAID");
    }

    @Test
    void aMaturityPaysTheWholeAccountOnTheDueDate() {
        // Commenced 15 years and two days ago, on a 180-month term: the maturity fell due yesterday.
        LocalDate start = LocalDate.now().minusYears(15).minusDays(2);
        var issued = fixtures.issue(TENANT, AccumulationTestFixtures.SAVINGS, start, 180);
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("100000.00"), start);
        dueDrain.drain();

        var maturity = asTenant(() -> benefitPayoutApi.listForPolicy(issued.policyNumber())).stream()
            .filter(i -> i.kind() == PayoutKind.MATURITY).findFirst().orElseThrow();
        List<LedgerEntryView> entries = entriesOf(issued.policyNumber());
        LedgerEntryView closing = entries.get(entries.size() - 1);
        assertThat(closing.type()).isEqualTo(EntryType.MATURITY);
        assertThat(maturity.currentAmount()).isEqualByComparingTo(closing.amount().negate());
        assertThat(closing.sourceRef()).isEqualTo("instalment:" + maturity.instalmentId());
        assertThat(asTenant(() -> policyApi.getPolicy(issued.policyNumber())).status()).isEqualTo(PolicyStatus.MATURED);
    }

    @Test
    void aDeathReversesWhatCameAfterItAndPaysTheBalanceAtDeath() {
        String policy = fundedWithMonthEnds();
        LocalDate death = LocalDate.now().withDayOfMonth(1).minusMonths(1).withDayOfMonth(10);
        // A premium collected after the death, and the month-end that ran after it.
        UUID late = UUID.randomUUID();
        fixtures.collectPremium(TENANT, policy, late, new BigDecimal("20000.00"), LocalDate.now());

        DeathValuation valuation = asTenant(() -> api.valueAtDeath(policy, death));
        assertThat(valuation.contributionsAfterDeath()).isEqualByComparingTo("20000.00");
        assertThat(valuation.premiumsBeforeDeath()).isEqualByComparingTo("100000.00");

        UUID claimId = UUID.randomUUID();
        fixtures.publish(TENANT, "claims.ClaimApproved", Map.of("claimId", claimId, "policyNumber", policy,
            "claimType", "DEATH", "dateOfEvent", death.toString(),
            "approvedAmount", Map.of("amount", valuation.accountValue().toPlainString(), "currencyCode", "TZS")));

        List<LedgerEntryView> entries = entriesOf(policy);
        LedgerEntryView closing = entries.get(entries.size() - 1);
        assertThat(closing.type()).isEqualTo(EntryType.DEATH_CLAIM);
        assertThat(closing.effectiveDate()).isEqualTo(death);
        assertThat(closing.amount().negate()).isEqualByComparingTo(valuation.accountValue());
        // Everything effective after the death -- the late premium, its charge, and the month-end
        // of the month the death fell in -- reversed, never edited.
        assertThat(entries).filteredOn(e -> e.type() == EntryType.REVERSAL).isNotEmpty()
            .allSatisfy(r -> assertThat(entries.stream().filter(e -> e.entryId().equals(r.reversesEntryId()))
                .findFirst().orElseThrow().effectiveDate()).isAfter(death));
        assertThat(entries).filteredOn(e -> e.effectiveDate().isAfter(death) && e.type() != EntryType.REVERSAL)
            .allSatisfy(e -> assertThat(entries).anyMatch(r -> e.entryId().equals(r.reversesEntryId())));
        assertThat(asTenant(() -> api.findAccount(policy)).orElseThrow().closedReason()).isEqualTo("DEATH");
    }

    @Test
    void theDeathCeilingIsTheAccountAtDeathPlusWhatWasPaidAfterIt() {
        String policy = fundedWithMonthEnds();
        LocalDate death = LocalDate.now().minusDays(1);
        fixtures.collectPremium(TENANT, policy, UUID.randomUUID(), new BigDecimal("20000.00"), LocalDate.now());
        DeathValuation v = asTenant(() -> api.valueAtDeath(policy, death));
        // The fixture authors no premium-percent floor, so the account is the base.
        assertThat(asTenant(() -> benefitPayoutApi.deathBenefitCeiling(policy, new BigDecimal("1000000.00"), death)))
            .isEqualByComparingTo(v.accountValue().add(new BigDecimal("20000.00")));
    }

    @Test
    void aScalePolicysCeilingIsUnchangedByTheDateOfDeath() {
        var scale = fixtures.issueSavingsPlan(TENANT, AccumulationPlan.none(), LocalDate.now());
        assertThat(asTenant(() -> benefitPayoutApi.deathBenefitCeiling(scale.policyNumber(), new BigDecimal("1000000.00"),
            LocalDate.now()))).isEqualByComparingTo("1000000.00");
    }

    @Test
    void aFreeLookCancellationEmptiesTheAccount() {
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now());
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("50000.00"), LocalDate.now());
        fixtures.publish(TENANT, "policy.PolicyCancelledFreeLook", Map.of("policyNumber", issued.policyNumber(),
            "cancelledAt", java.time.Instant.now().toString(), "cancelledBy", "finance-two"));
        List<LedgerEntryView> entries = entriesOf(issued.policyNumber());
        assertThat(entries.get(entries.size() - 1).type()).isEqualTo(EntryType.FREE_LOOK_REFUND);
        assertThat(entries.get(entries.size() - 1).balanceAfter()).isEqualByComparingTo("0.00");
        assertThat(asTenant(() -> api.findAccount(issued.policyNumber())).orElseThrow().closedReason()).isEqualTo("FREE_LOOK");
    }

    @Test
    void aPremiumAfterClosingIsNotCredited() {
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now());
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("50000.00"), LocalDate.now());
        fixtures.publish(TENANT, "policy.PolicyCancelledFreeLook", Map.of("policyNumber", issued.policyNumber(),
            "cancelledAt", java.time.Instant.now().toString(), "cancelledBy", "finance-two"));
        int closed = entriesOf(issued.policyNumber()).size();
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("50000.00"), LocalDate.now());
        assertThat(entriesOf(issued.policyNumber())).hasSize(closed);
    }
}
