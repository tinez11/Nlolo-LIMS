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
import tz.co.nlolo.lifeplatform.accumulation.application.AccumulationApiImpl;
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
 * Declared rates: two people, and never reaching back past interest a customer has already been
 * credited -- checked at proposal and again at approval.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(AccumulationTestFixtures.class)
class RateDeclarationIntegrationTest {

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

    private <T> T asTenant(java.util.function.Supplier<T> work) {
        TenantContext.set(TENANT);
        try { return work.get(); } finally { TenantContext.clear(); }
    }

    private UUID productWithMonthEndsThrough(LocalDate start) {
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, start);
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("100000.00"), start);
        asTenant(() -> { api.postMonthEnds(issued.policyNumber(), LocalDate.now()); return null; });
        return issued.productId();
    }

    @Test
    void theProposerCannotApprove() {
        UUID product = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now()).productId();
        var proposal = asTenant(() -> api.proposeRate(product, new BigDecimal("6"), LocalDate.now().plusMonths(1), "admin-one"));
        assertThatThrownBy(() -> asTenant(() -> api.approveRate(proposal.declarationId(), "admin-one")))
            .isInstanceOf(AccumulationStateException.class)
            .hasMessage("A declared rate must be approved by someone other than the person who proposed it");
        assertThat(asTenant(() -> api.approveRate(proposal.declarationId(), "finance-two")).status())
            .isEqualTo(RateDeclarationStatus.APPROVED);
    }

    @Test
    void aRateReachingBackPastPostedInterestIsRefused() {
        LocalDate start = LocalDate.now().withDayOfMonth(1).minusMonths(2);
        UUID product = productWithMonthEndsThrough(start);
        LocalDate lastPosted = LocalDate.now().withDayOfMonth(1).minusDays(1);
        assertThatThrownBy(() -> asTenant(() -> api.proposeRate(product, new BigDecimal("6"), lastPosted, "admin-one")))
            .isInstanceOf(AccumulationStateException.class)
            .hasMessageContaining("already credited up to " + lastPosted);
        assertThat(asTenant(() -> api.proposeRate(product, new BigDecimal("6"), lastPosted.plusDays(1), "admin-one")))
            .isNotNull();
    }

    @Test
    void aRateProposedInTimeButApprovedTooLateIsRefusedAtApproval() {
        LocalDate start = LocalDate.now().withDayOfMonth(1).minusMonths(2);
        var issued = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, start);
        fixtures.collectPremium(TENANT, issued.policyNumber(), UUID.randomUUID(), new BigDecimal("100000.00"), start);
        // Proposed while nothing is posted...
        var proposal = asTenant(() -> api.proposeRate(issued.productId(), new BigDecimal("6"), start.plusDays(1), "admin-one"));
        // ...then the month-end runs before anyone approves it.
        asTenant(() -> { api.postMonthEnds(issued.policyNumber(), LocalDate.now()); return null; });
        assertThatThrownBy(() -> asTenant(() -> api.approveRate(proposal.declarationId(), "finance-two")))
            .isInstanceOf(AccumulationStateException.class)
            .hasMessageContaining("would rewrite it");
    }

    @Test
    void anApprovedRateCannotBeWithdrawn() {
        UUID product = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now()).productId();
        var proposal = asTenant(() -> api.proposeRate(product, new BigDecimal("6"), LocalDate.now().plusMonths(1), "admin-one"));
        asTenant(() -> api.approveRate(proposal.declarationId(), "finance-two"));
        assertThatThrownBy(() -> asTenant(() -> api.withdrawRate(proposal.declarationId(), "admin-one")))
            .isInstanceOf(AccumulationStateException.class)
            .hasMessage("This rate declaration is approved, not awaiting approval");
    }

    @Test
    void twoApprovedRatesOnOneDayAreRefusedByName() {
        UUID product = fixtures.issueSavingsPlan(TENANT, AccumulationTestFixtures.SAVINGS, LocalDate.now()).productId();
        LocalDate day = LocalDate.now().plusMonths(1);
        var first = asTenant(() -> api.proposeRate(product, new BigDecimal("6"), day, "admin-one"));
        var second = asTenant(() -> api.proposeRate(product, new BigDecimal("7"), day, "admin-one"));
        asTenant(() -> api.approveRate(first.declarationId(), "finance-two"));
        assertThatThrownBy(() -> asTenant(() -> api.approveRate(second.declarationId(), "finance-two")))
            .isInstanceOf(AccumulationStateException.class)
            .hasMessageContaining("already approved for this product from " + day);
    }
}
