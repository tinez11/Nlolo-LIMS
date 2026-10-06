package tz.co.nlolo.lifeplatform.benefitpayout;

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
import tz.co.nlolo.lifeplatform.benefitpayout.api.BenefitPayoutApi;
import tz.co.nlolo.lifeplatform.benefitpayout.api.InstalmentStatus;
import tz.co.nlolo.lifeplatform.benefitpayout.api.PayoutInstalmentView;
import tz.co.nlolo.lifeplatform.product.api.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A policy issued on a version that pays while the life assured lives gets its whole schedule
 * stored at issue -- the guide's "calculated and stored on the day the policy is issued" (§16).
 *
 * <p>Through the REAL issuance path and the REAL event, not by calling the expander: the seam this
 * exists to cover is whether policy.PolicyIssued actually reaches this module carrying what it
 * needs, which no unit test of the expander can show.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(PayoutTestFixtures.class)
class BenefitPayoutApiIntegrationTest {

    private static final UUID TENANT = UUID.randomUUID();

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
            "db-migrations/audit/V3__q4_2026_partitions.sql");
    }

    @Autowired private BenefitPayoutApi api;
    @Autowired private PayoutTestFixtures fixtures;

    private static final PayoutPlan MONEY_BACK = PayoutPlan.authored(
        new PayoutTerms(15, null, true, null),
        List.of(
            new PayoutRowInput(PayoutKind.SURVIVAL, 5, 5, PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("10"), PayoutFrequency.ANNUAL),
            new PayoutRowInput(PayoutKind.MATURITY, null, null, PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("100"), null)));

    @Test
    void issuingAMoneyBackEndowmentStoresItsWholeSchedule() {
        String policyNumber = fixtures.issueEndowment(TENANT, MONEY_BACK, new BigDecimal("1000000.00"), 240);

        TenantContext.set(TENANT);
        try {
            List<PayoutInstalmentView> schedule = api.listForPolicy(policyNumber);
            assertThat(schedule).extracting(PayoutInstalmentView::kind)
                .containsExactly(PayoutKind.SURVIVAL, PayoutKind.MATURITY);
            assertThat(schedule).allMatch(v -> v.status() == InstalmentStatus.SCHEDULED);
            // 10% of a million, on the fifth anniversary of the day risk commenced.
            assertThat(schedule.get(0).currentAmount()).isEqualByComparingTo("100000.00");
            assertThat(schedule.get(0).dueDate()).isEqualTo(LocalDate.now().plusYears(5));
            assertThat(schedule.get(1).currentAmount()).isEqualByComparingTo("1000000.00");
            // Claims must refuse a manual maturity claim on this policy: it is already owed.
            assertThat(api.hasScheduledMaturity(policyNumber)).isTrue();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void aPolicyOnAVersionWithNoPlanGetsNoInstalments() {
        // Every policy sold before step 2, and every term policy after it.
        String policyNumber = fixtures.issueEndowment(TENANT, PayoutPlan.none(), new BigDecimal("500000.00"), 120);

        TenantContext.set(TENANT);
        try {
            assertThat(api.listForPolicy(policyNumber)).isEmpty();
            assertThat(api.hasScheduledMaturity(policyNumber)).isFalse();
        } finally {
            TenantContext.clear();
        }
    }
}
