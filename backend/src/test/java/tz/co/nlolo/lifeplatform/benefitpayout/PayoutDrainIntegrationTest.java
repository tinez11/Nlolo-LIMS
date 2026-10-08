package tz.co.nlolo.lifeplatform.benefitpayout;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.benefitpayout.api.*;
import tz.co.nlolo.lifeplatform.benefitpayout.application.PayoutDueDrain;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyStatus;
import tz.co.nlolo.lifeplatform.product.api.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Instalments falling due, the arrears hold, the two-person review, and a policy maturing rather
 * than expiring -- through the real drain and the real APIs.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(PayoutTestFixtures.class)
class PayoutDrainIntegrationTest {

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
            "db-migrations/product/V29__funeral_group_rate.sql",
            "db-migrations/product/V30__funeral_group_rate_period.sql",
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
            "db-migrations/underwriting/V19__group_funeral_proposal.sql",
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

    @Autowired private PayoutDueDrain drain;
    @Autowired private BenefitPayoutApi api;
    @Autowired private PolicyApi policyApi;
    @Autowired private PayoutTestFixtures fixtures;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final PayoutPlan MONEY_BACK = PayoutPlan.authored(
        new PayoutTerms(15, null, false, null),
        List.of(
            new PayoutRowInput(PayoutKind.SURVIVAL, 5, 5, PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("10"), PayoutFrequency.ANNUAL),
            new PayoutRowInput(PayoutKind.MATURITY, null, null, PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("100"), null)));

    /** Commenced five years and a day ago, so year 5's survival benefit fell due yesterday. */
    private String moneyBackDueYesterday() {
        return fixtures.issueEndowment(TENANT, MONEY_BACK, new BigDecimal("1000000.00"), 240,
            LocalDate.now().minusYears(5).minusDays(1));
    }

    private PayoutInstalmentView survival(String policyNumber) {
        TenantContext.set(TENANT);
        try {
            return api.listForPolicy(policyNumber).stream()
                .filter(v -> v.kind() == PayoutKind.SURVIVAL).findFirst().orElseThrow();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void anUnpaidPolicyHoldsAndAPaymentReleasesIt() {
        String policyNumber = moneyBackDueYesterday();
        /*
          The tally reckons premium due dates from the ISSUE date, which the API sets to today even
          though cover commenced five years ago -- so no premium would be due before the payout and
          the hold could never trigger. That would be a test passing while proving nothing.
          Backdating the tally makes five years of monthly premiums fall due with none paid. The
          test connects as the table owner, so RLS is bypassed.
        */
        jdbcTemplate.update("UPDATE benefitpayout.premium_tally SET issue_date = current_date - 1 - interval '5 years' "
            + "WHERE policy_number = ?", policyNumber);

        drain.drain();
        assertThat(survival(policyNumber).status()).isEqualTo(InstalmentStatus.ON_HOLD);
        assertThat(survival(policyNumber).statusReason()).isEqualTo("Premiums are not paid up to the due date");

        fixtures.collectPremium(TENANT, policyNumber, new BigDecimal("3000000.00"), LocalDate.now().minusDays(1));
        assertThat(survival(policyNumber).status()).isEqualTo(InstalmentStatus.DUE);
    }

    @Test
    void reviewThenApproveByADifferentPersonRequestsThePayout() {
        String policyNumber = moneyBackDueYesterday();
        fixtures.collectPremium(TENANT, policyNumber, new BigDecimal("3000000.00"), LocalDate.now().minusDays(1));
        drain.drain();
        UUID id = survival(policyNumber).instalmentId();

        TenantContext.set(TENANT);
        try {
            api.review(id, "+255700000009", ProofOfLifeMethod.PHONE_OR_VIDEO, null, "reviewer-1");
            assertThatThrownBy(() -> api.approve(id, "reviewer-1"))
                .isInstanceOf(PayoutStateException.class)
                .hasMessageContaining("other than the person who reviewed it");

            PayoutInstalmentView approved = api.approve(id, "approver-2");
            assertThat(approved.status()).isEqualTo(InstalmentStatus.APPROVED);
            assertThat(approved.approvedBy()).isEqualTo("approver-2");
            assertThat(approved.payeeRef()).isEqualTo("+255700000009");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void aSurvivalPayoutCannotBeReviewedWithoutProofOfLife() {
        String policyNumber = moneyBackDueYesterday();
        fixtures.collectPremium(TENANT, policyNumber, new BigDecimal("3000000.00"), LocalDate.now().minusDays(1));
        drain.drain();
        UUID id = survival(policyNumber).instalmentId();

        TenantContext.set(TENANT);
        try {
            assertThatThrownBy(() -> api.review(id, "+255700000009", null, null, "reviewer-1"))
                .hasMessage("A SURVIVAL payout needs proof that the life assured is alive");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void aMaturityDateArrivingMaturesThePolicyInsteadOfExpiringIt() {
        // A twelve-month endowment commenced a year and a day ago: its maturity date was
        // yesterday, so the expiry sweep and the payout drain both have a claim on it.
        PayoutPlan endowment = PayoutPlan.authored(new PayoutTerms(15, null, null, null), List.of(
            new PayoutRowInput(PayoutKind.MATURITY, null, null, PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("100"), null)));
        String policyNumber = fixtures.issueEndowment(TENANT, endowment, new BigDecimal("500000.00"), 12,
            LocalDate.now().minusYears(1).minusDays(1));

        TenantContext.set(TENANT);
        try {
            // The expiry sweep must leave it alone: EXPIRED is terminal and pays nothing, so
            // expiring it would strand the maturity benefit the customer paid for.
            policyApi.expirePolicy(policyNumber);
            assertThat(policyApi.getPolicy(policyNumber).status()).isEqualTo(PolicyStatus.ACTIVE);
        } finally {
            TenantContext.clear();
        }

        drain.drain();

        TenantContext.set(TENANT);
        try {
            assertThat(policyApi.getPolicy(policyNumber).status()).isEqualTo(PolicyStatus.MATURED);
            assertThat(api.listForPolicy(policyNumber).get(0).status())
                .isIn(InstalmentStatus.DUE, InstalmentStatus.ON_HOLD);
        } finally {
            TenantContext.clear();
        }
    }
}
