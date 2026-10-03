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
import tz.co.nlolo.lifeplatform.product.api.AccumulationChargeRow;
import tz.co.nlolo.lifeplatform.product.api.AccumulationPlan;
import tz.co.nlolo.lifeplatform.product.api.ValueBasis;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * billing.PremiumCollected reaches the account: credited once per invoice, less the allocation charge of
 * the policy year the money arrived in, and never on a scale policy.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(AccumulationTestFixtures.class)
class ContributionIntegrationTest {

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
            "db-migrations/accumulation/V1__create_accumulation_schema.sql",
            "db-migrations/benefitpayout/V1__create_benefitpayout_schema.sql",
            "db-migrations/benefitpayout/V2__annuity_streams.sql",
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
    @Autowired private PolicyApi policyApi;

    private List<LedgerEntryView> entriesOf(String policyNumber) {
        TenantContext.set(TENANT);
        try { return api.entries(policyNumber); } finally { TenantContext.clear(); }
    }

    @Test
    void aCollectedPremiumIsCreditedLessTheYearOneAllocationCharge() {
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now());
        UUID invoice = UUID.randomUUID();
        fixtures.collectPremium(TENANT, issued.policyNumber(), invoice, new BigDecimal("50000.00"), LocalDate.now());

        assertThat(entriesOf(issued.policyNumber()))
            .extracting(LedgerEntryView::type, e -> e.amount().toPlainString(), e -> e.balanceAfter().toPlainString())
            .containsExactly(
                tuple(EntryType.CONTRIBUTION, "50000.00", "50000.00"),
                // 5% in policy year 1.
                tuple(EntryType.ALLOCATION_CHARGE, "-2500.00", "47500.00"));
        assertThat(entriesOf(issued.policyNumber())).allSatisfy(e ->
            assertThat(e.sourceRef()).isEqualTo("invoice:" + invoice));
    }

    @Test
    void theYearIsTheYearTheMoneyArrivedIn() {
        // Commenced 13 months ago: this premium is collected in policy year 2, where the charge is 1%.
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now().minusMonths(13));
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("50000.00"), LocalDate.now());
        assertThat(entriesOf(issued.policyNumber())).extracting(e -> e.amount().toPlainString())
            .containsExactly("50000.00", "-500.00");
    }

    @Test
    void aRedeliveredPremiumPostsNothingTheSecondTime() {
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now());
        UUID invoice = UUID.randomUUID();
        fixtures.collectPremium(TENANT, issued.policyNumber(), invoice, new BigDecimal("50000.00"), LocalDate.now());
        fixtures.collectPremium(TENANT, issued.policyNumber(), invoice, new BigDecimal("50000.00"), LocalDate.now());
        fixtures.collectPremium(TENANT, issued.policyNumber(), invoice, new BigDecimal("50000.00"), LocalDate.now());
        assertThat(entriesOf(issued.policyNumber())).hasSize(2);
        TenantContext.set(TENANT);
        try {
            assertThat(policyApi.getCashValue(issued.policyNumber()).cashValueAmount()).isEqualByComparingTo("47500.00");
        } finally { TenantContext.clear(); }
    }

    @Test
    void aPremiumOnAScalePolicyIsIgnored() {
        var scale = fixtures.issueSavingsPlan(TENANT, AccumulationPlan.none(), LocalDate.now());
        fixtures.collectPremium(TENANT, scale.policyNumber(), UUID.randomUUID(), new BigDecimal("50000.00"), LocalDate.now());
        assertThat(entriesOf(scale.policyNumber())).isEmpty();
    }

    @Test
    void aPremiumPaidBeforeAFutureCommencementIsDatedToTheDayCoverStarts() {
        LocalDate starts = LocalDate.now().plusDays(10);
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, starts);
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("50000.00"), LocalDate.now());
        assertThat(entriesOf(issued.policyNumber())).extracting(LedgerEntryView::effectiveDate).containsOnly(starts);
    }

    @Test
    void aZeroPercentYearWritesNoChargeEntry() {
        var free = new AccumulationPlan(ValueBasis.ACCOUNT, new BigDecimal("3"), new BigDecimal("50000.00"),
            List.of(new AccumulationChargeRow(1, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)));
        var issued = fixtures.issueSavingsPlan(TENANT, free, LocalDate.now());
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("50000.00"), LocalDate.now());
        assertThat(entriesOf(issued.policyNumber())).extracting(LedgerEntryView::type).containsExactly(EntryType.CONTRIBUTION);
    }

    @Test
    void theChargeIsRoundedOnceHalfEven() {
        var odd = new AccumulationPlan(ValueBasis.ACCOUNT, new BigDecimal("3"), new BigDecimal("50000.00"),
            List.of(new AccumulationChargeRow(1, null, new BigDecimal("2.5"), BigDecimal.ZERO, BigDecimal.ZERO)));
        var issued = fixtures.issueSavingsPlan(TENANT, odd, LocalDate.now());
        // 2.5% of 1,001.00 = 25.025 -> 25.02 (half-even), not 25.03.
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("1001.00"), LocalDate.now());
        assertThat(entriesOf(issued.policyNumber())).extracting(e -> e.amount().toPlainString())
            .containsExactly("1001.00", "-25.02");
    }
}
