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
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyStatus;
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

/**
 * The month-end run against a real database: interest then the fee for each completed month, once per
 * policy and month however often it runs, nothing for an account never paid into, and an account that
 * cannot pay its fee closing at zero and lapsing its policy.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(AccumulationTestFixtures.class)
class MonthEndIntegrationTest {

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
            "db-migrations/product/V21__bonus_terms.sql",
            "db-migrations/product/V27__ifrs17_classification.sql",
            "db-migrations/product/V28__survival_investment_component.sql",
            "db-migrations/product/V29__funeral_group_rate.sql",
            "db-migrations/product/V30__funeral_group_rate_period.sql",
            "db-migrations/product/V31__online_listing.sql",
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
            "db-migrations/audit/V3__q4_2026_partitions.sql");
    }

    private static final UUID TENANT = UUID.randomUUID();

    @Autowired private AccumulationTestFixtures fixtures;
    @Autowired private AccumulationApiImpl api;
    @Autowired private PolicyApi policyApi;

    private <T> T asTenant(java.util.function.Supplier<T> work) {
        TenantContext.set(TENANT);
        try { return work.get(); } finally { TenantContext.clear(); }
    }
    private void runMonthEnds(String policyNumber, LocalDate today) {
        asTenant(() -> { api.postMonthEnds(policyNumber, today); return null; });
    }
    private List<LedgerEntryView> entriesOf(String p) { return asTenant(() -> api.entries(p)); }

    /** Commenced on the 1st of the month three months ago, 100,000 paid on day one. */
    private String funded() {
        LocalDate start = LocalDate.now().withDayOfMonth(1).minusMonths(3);
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, start);
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("100000.00"), start);
        return issued.policyNumber();
    }

    @Test
    void eachCompletedMonthPostsInterestThenTheFee() {
        String policy = funded();
        runMonthEnds(policy, LocalDate.now());
        List<LedgerEntryView> monthEnds = entriesOf(policy).stream()
            .filter(e -> e.sourceType().equals("month-end")).toList();
        // Three completed months, two entries each.
        assertThat(monthEnds).extracting(LedgerEntryView::type).containsExactly(
            EntryType.INTEREST, EntryType.POLICY_FEE, EntryType.INTEREST, EntryType.POLICY_FEE,
            EntryType.INTEREST, EntryType.POLICY_FEE);
        assertThat(monthEnds).filteredOn(e -> e.type() == EntryType.POLICY_FEE)
            .allSatisfy(e -> assertThat(e.amount()).isEqualByComparingTo("-1000.00"));
        assertThat(monthEnds).filteredOn(e -> e.type() == EntryType.INTEREST)
            .allSatisfy(e -> assertThat(e.amount()).isPositive());
        // Each dated to its own month end.
        assertThat(monthEnds).allSatisfy(e ->
            assertThat(e.effectiveDate()).isEqualTo(e.effectiveDate().withDayOfMonth(e.effectiveDate().lengthOfMonth())));
    }

    @Test
    void runningTheMonthEndTwicePostsOnce() {
        String policy = funded();
        runMonthEnds(policy, LocalDate.now());
        int after = entriesOf(policy).size();
        runMonthEnds(policy, LocalDate.now());
        assertThat(entriesOf(policy)).hasSize(after);
    }

    @Test
    void twoPoliciesInTheSameMonthBothPost() {
        // The source ref names the policy; without it the second account's month would be
        // refused as a duplicate of the first's.
        String a = funded();
        String b = funded();
        runMonthEnds(a, LocalDate.now());
        runMonthEnds(b, LocalDate.now());
        assertThat(entriesOf(b)).anyMatch(e -> e.type() == EntryType.INTEREST);
    }

    @Test
    void aPartFirstMonthChargesNoFee() {
        LocalDate midMonth = LocalDate.now().withDayOfMonth(1).minusMonths(1).withDayOfMonth(15);
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, midMonth);
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("100000.00"), midMonth);
        runMonthEnds(issued.policyNumber(), LocalDate.now());
        assertThat(entriesOf(issued.policyNumber())).noneMatch(e -> e.type() == EntryType.POLICY_FEE);
    }

    @Test
    void anAccountNeverPaidIntoPostsNothingAndIsNotLapsed() {
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS,
            LocalDate.now().withDayOfMonth(1).minusMonths(2));
        runMonthEnds(issued.policyNumber(), LocalDate.now());
        assertThat(entriesOf(issued.policyNumber())).isEmpty();
        assertThat(asTenant(() -> policyApi.getPolicy(issued.policyNumber())).status()).isNotEqualTo(PolicyStatus.LAPSED);
    }

    @Test
    void aFeeLargerThanTheBalanceTakesWhatIsThereClosesAndLapses() {
        LocalDate start = LocalDate.now().withDayOfMonth(1).minusMonths(3);
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, start);
        // 1,500 in, 5% allocation: 1,425. One fee of 1,000 leaves ~425 plus interest; the next takes the rest.
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("1500.00"), start);
        runMonthEnds(issued.policyNumber(), LocalDate.now());

        List<LedgerEntryView> entries = entriesOf(issued.policyNumber());
        LedgerEntryView last = entries.get(entries.size() - 1);
        assertThat(last.type()).isEqualTo(EntryType.POLICY_FEE);
        assertThat(last.balanceAfter()).isEqualByComparingTo("0.00");
        // A fee smaller than 1,000 -- it took only what was there.
        assertThat(last.amount().negate()).isLessThan(new BigDecimal("1000.00"));
        // And nothing after it: the closed account is not charged for the third month.
        assertThat(entries.stream().filter(e -> e.type() == EntryType.POLICY_FEE)).hasSize(2);
        assertThat(asTenant(() -> api.findAccount(issued.policyNumber())).orElseThrow().closedReason()).isEqualTo("EXHAUSTED");
        assertThat(asTenant(() -> policyApi.getPolicy(issued.policyNumber())).status()).isEqualTo(PolicyStatus.LAPSED);
    }

    @Test
    void aDeclaredRateAboveTheGuaranteeEarnsMoreThanTheGuarantee() {
        String guaranteed = funded();
        String declared = funded();
        // Approve 8% on the second product, effective long before its first month-end.
        UUID productId = asTenant(() -> api.findAccount(declared)).orElseThrow().productId();
        LocalDate from = LocalDate.now().withDayOfMonth(1).minusMonths(3);
        var proposal = asTenant(() -> api.proposeRate(productId, new BigDecimal("8"), from, "admin-one"));
        asTenant(() -> api.approveRate(proposal.declarationId(), "finance-two"));
        runMonthEnds(guaranteed, LocalDate.now());
        runMonthEnds(declared, LocalDate.now());
        assertThat(interestOf(declared)).isGreaterThan(interestOf(guaranteed));
    }

    private BigDecimal interestOf(String p) {
        return entriesOf(p).stream().filter(e -> e.type() == EntryType.INTEREST)
            .map(LedgerEntryView::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void reinstatementReopensAnExhaustedAccount() {
        LocalDate start = LocalDate.now().withDayOfMonth(1).minusMonths(3);
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, start);
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("1500.00"), start);
        runMonthEnds(issued.policyNumber(), LocalDate.now());
        fixtures.publish(TENANT, "policy.PolicyReinstated", Map.of("policyNumber", issued.policyNumber(),
            "reinstatedAt", java.time.Instant.now().toString()));
        assertThat(asTenant(() -> api.findAccount(issued.policyNumber())).orElseThrow().status()).isEqualTo(AccountStatus.OPEN);
    }
}
