package tz.co.nlolo.lifeplatform.distribution;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.distribution.api.AgentView;
import tz.co.nlolo.lifeplatform.distribution.api.DistributionApi;
import tz.co.nlolo.lifeplatform.distribution.api.StatementStatus;
import tz.co.nlolo.lifeplatform.distribution.api.TierType;
import tz.co.nlolo.lifeplatform.distribution.domain.CommissionAccrual;
import tz.co.nlolo.lifeplatform.distribution.domain.CommissionStatement;
import tz.co.nlolo.lifeplatform.distribution.domain.PolicyProjectionId;
import tz.co.nlolo.lifeplatform.distribution.infrastructure.CommissionAccrualRepository;
import tz.co.nlolo.lifeplatform.distribution.infrastructure.CommissionStatementRepository;
import tz.co.nlolo.lifeplatform.distribution.infrastructure.PolicyProjectionRepository;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.product.api.BenefitType;
import tz.co.nlolo.lifeplatform.product.api.FactorType;
import tz.co.nlolo.lifeplatform.product.api.IfrsMeasurementModel;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.ProductCategory;
import tz.co.nlolo.lifeplatform.product.api.ProductSummaryView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;
import tz.co.nlolo.lifeplatform.product.api.BenefitCalculationMethod;

/**
 * Task 6, Step 4 -- the clawback half of the accrual/clawback pair, exercised through the real
 * chain: {@code policyApi.lapsePolicy} -> the real {@code policy.PolicyLapsed} event -> {@code
 * distribution.application.PolicyEventListener.handlePolicyLapsed}.
 *
 * <p>Runs against real Postgres as {@code app_role} (NOSUPERUSER NOBYPASSRLS), same bootstrap as
 * {@code CommissionAccrualEndToEndTest}. {@code TZ_COMMISSION_CLAWBACK_MONTHS} comes from the real
 * seeded refdata row (V4 -- 12 months, a flagged placeholder per Task 2), not a stub.
 *
 * <p>Two scenarios need a state no published API can drive to (a statement already {@code PAID},
 * and a policy issued long enough ago to be outside the window) -- reached the same way {@code
 * ClaimSettlementEndToEndTest} reaches a {@code PROPOSED} policy: a direct write over a superuser
 * connection (never {@code app_role}), since RLS is irrelevant to a fixture setup step and no
 * production code path can manufacture either state within a single test's runtime.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class ClawbackIntegrationTest {

    private static final String APP_ROLE_PASSWORD = "clawback_it_password";
    private static final String CURRENCY = "TZS";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "app_role");
        registry.add("spring.datasource.password", () -> APP_ROLE_PASSWORD);
    }

    @BeforeAll
    static void applyMigrationsAndBootstrapAppRole() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/refdata/V3__seed_billing_parameters.sql",
            "db-migrations/refdata/V4__seed_distribution_parameters.sql",
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
            "db-migrations/benefitpayout/V1__create_benefitpayout_schema.sql",
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
            "db-migrations/distribution/V1__create_distribution_schema.sql",
            "db-migrations/distribution/V2__grants_rls_money_checks_projection_and_statement_lifecycle.sql");
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE app_role LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '"
                + APP_ROLE_PASSWORD + "'");
        }
    }

    @Autowired private DistributionApi distributionApi;
    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private PolicyApi policyApi;
    @Autowired private PolicyProjectionRepository policyProjectionRepository;
    @Autowired private CommissionAccrualRepository commissionAccrualRepository;
    @Autowired private CommissionStatementRepository commissionStatementRepository;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private record Fixture(UUID sellerId, UUID productId, UUID productVersionId) {}

    private Fixture buildFixture(UUID tenantId, String tag) {
        TenantContext.set(tenantId);
        ProductSummaryView product = productApi.createProduct("DIST-CLAW-" + tag, "Clawback IT Product " + tag,
            ProductCategory.TERM_LIFE, CURRENCY, "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary");
        UUID productVersionId = productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId();

        distributionApi.createCommissionPlan(product.productId(), List.of(
            new DistributionApi.CommissionRuleInput(TierType.FIRST_YEAR, new BigDecimal("0.10"), null, null)),
            "actuary");

        PartyView agentParty = partyApi.registerIndividual("Clawback IT Agent " + tag, LocalDate.of(1985, 1, 1),
            "+25571" + String.format("%07d", Math.abs(tag.hashCode() % 10000000)), null, "test-agent");
        partyApi.submitKycEvidence(agentParty.partyId(), tz.co.nlolo.lifeplatform.party.api.KycStatus.VERIFIED,
            "doc-ref-" + tag, "kyc-officer");
        AgentView agent = distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            agentParty.partyId(), "LIC-CLAW-" + tag, LocalDate.now().plusYears(1), null), "staff-1");

        return new Fixture(agent.agentId(), product.productId(), productVersionId);
    }

    private String issuePolicy(UUID tenantId, Fixture fixture, BigDecimal premium, String tag) {
        TenantContext.set(tenantId);
        PartyView policyholder = partyApi.registerIndividual("Clawback IT Policyholder " + tag,
            LocalDate.of(1980, 6, 1), "+25572" + String.format("%07d", Math.abs(tag.hashCode() % 10000000)), null, "test-agent");
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(policyholder.partyId(), fixture.productId(),
            fixture.productVersionId(), new BigDecimal("2000000"), CURRENCY, premium, CURRENCY, "MONTHLY",
            fixture.sellerId(), List.of(), "Clawback IT test");
        String issuedPolicyNumber = policyApi.issuePolicy(null, request, "test-staff").policyNumber();
        // Cover starts with the first premium. This fixture needs a policy on risk.
        policyApi.activateOnFirstPremium(issuedPolicyNumber);
        return issuedPolicyNumber;
    }

    private CommissionAccrual originalFirstYearAccrual(UUID tenantId, String policyNumber) {
        List<CommissionAccrual> accruals = commissionAccrualRepository
            .findByTenantIdAndPolicyNumberAndTierTypeAndReversesAccrualIdIsNull(tenantId, policyNumber, TierType.FIRST_YEAR);
        assertThat(accruals).as("expected a real FIRST_YEAR accrual from the real issuance chain").hasSize(1);
        return accruals.get(0);
    }

    private List<CommissionAccrual> reversalsOf(UUID tenantId, UUID originalAccrualId) {
        return commissionAccrualRepository.findAll().stream()
            .filter(a -> tenantId.equals(a.getTenantId()) && originalAccrualId.equals(a.getReversesAccrualId()))
            .toList();
    }

    /** Backdates {@code distribution.commission_statement}'s period and marks it PAID, over a
     * superuser connection -- no published API can reach PAID (Task 6's own scope stops at
     * accrual/clawback), and backdating the period is what keeps this statement genuinely
     * DIFFERENT from "today's" open statement the clawback will target, exactly as a real PAID
     * statement from a prior, already-closed-and-paid period would be. */
    private void backdateAndMarkStatementPaid(UUID statementId, String period) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement update = connection.prepareStatement(
                 "UPDATE distribution.commission_statement SET period = ?, status = 'PAID', paid_at = now() "
                     + "WHERE statement_id = ?")) {
            update.setString(1, period);
            update.setObject(2, statementId);
            update.executeUpdate();
        }
    }

    /** Backdates {@code distribution.policy_projection.issue_date}, over a superuser connection,
     * to simulate a policy old enough to have fallen outside the clawback window -- something no
     * published API can do (issue dates are always "today" per {@code PolicyApiImpl.issuePolicy}). */
    private void backdateProjectionIssueDate(UUID tenantId, String policyNumber, LocalDate issueDate) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement update = connection.prepareStatement(
                 "UPDATE distribution.policy_projection SET issue_date = ? WHERE tenant_id = ? AND policy_number = ?")) {
            update.setObject(1, issueDate);
            update.setObject(2, tenantId);
            update.setString(3, policyNumber);
            update.executeUpdate();
        }
    }

    @Test
    void aLapseInsideTheWindowReversesTheFirstYearAccrualLeavingTheOriginalRowIntact() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "INSIDE");
        String policyNumber = issuePolicy(tenantId, fixture, new BigDecimal("100000.00"), "INSIDE-01");

        TenantContext.set(tenantId);
        CommissionAccrual original = originalFirstYearAccrual(tenantId, policyNumber);
        assertThat(original.getAmount()).isEqualByComparingTo("10000.00");

        // Lapsed "now" -- 0 months after an issue date of "today", well inside the 12-month
        // default window (TZ_COMMISSION_CLAWBACK_MONTHS, V4's real seeded value).
        policyApi.lapsePolicy(policyNumber, "test-staff");

        TenantContext.set(tenantId);
        // The original row is untouched -- append-only, never mutated.
        CommissionAccrual originalAfter = commissionAccrualRepository.findById(original.getAccrualId()).orElseThrow();
        assertThat(originalAfter.getAmount()).isEqualByComparingTo("10000.00");
        assertThat(originalAfter.getReversesAccrualId()).isNull();

        // A reversal exists, negated, pointing back at the original.
        List<CommissionAccrual> reversals = reversalsOf(tenantId, original.getAccrualId());
        assertThat(reversals).hasSize(1);
        CommissionAccrual reversal = reversals.get(0);
        assertThat(reversal.getAmount()).isEqualByComparingTo("-10000.00");
        assertThat(reversal.getAgentId()).isEqualTo(fixture.sellerId());
        assertThat(reversal.getSourceRef()).isEqualTo(original.getAccrualId().toString());

        // Same calendar period as the (same-day) issuance, so the reversal lands in the SAME
        // statement as the original -- net total zero, read back from the database.
        assertThat(reversal.getStatementId()).isEqualTo(original.getStatementId());
        CommissionStatement statement = commissionStatementRepository
            .findByStatementIdAndTenantId(original.getStatementId(), tenantId).orElseThrow();
        assertThat(statement.getTotalAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void aLapseOutsideTheWindowChangesNothing() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "OUTSIDE");
        String policyNumber = issuePolicy(tenantId, fixture, new BigDecimal("100000.00"), "OUTSIDE-01");

        TenantContext.set(tenantId);
        CommissionAccrual original = originalFirstYearAccrual(tenantId, policyNumber);
        CommissionStatement statementBefore = commissionStatementRepository
            .findByStatementIdAndTenantId(original.getStatementId(), tenantId).orElseThrow();
        BigDecimal totalBefore = statementBefore.getTotalAmount();

        // 20 months before "now" -- well beyond the 12-month default window.
        backdateProjectionIssueDate(tenantId, policyNumber, LocalDate.now().minusMonths(20));

        policyApi.lapsePolicy(policyNumber, "test-staff");

        TenantContext.set(tenantId);
        // No reversal was created.
        assertThat(reversalsOf(tenantId, original.getAccrualId())).isEmpty();
        assertThat(commissionAccrualRepository
            .findByTenantIdAndPolicyNumberAndTierTypeAndReversesAccrualIdIsNull(tenantId, policyNumber, TierType.FIRST_YEAR))
            .hasSize(1);
        CommissionStatement statementAfter = commissionStatementRepository
            .findByStatementIdAndTenantId(original.getStatementId(), tenantId).orElseThrow();
        assertThat(statementAfter.getTotalAmount()).isEqualByComparingTo(totalBefore);
    }

    @Test
    void aLapseAgainstAnAlreadyPaidStatementBooksTheReversalInTheOpenPeriodAndLeavesThePaidTotalUntouched() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "PAID");
        String policyNumber = issuePolicy(tenantId, fixture, new BigDecimal("100000.00"), "PAID-01");

        TenantContext.set(tenantId);
        CommissionAccrual original = originalFirstYearAccrual(tenantId, policyNumber);
        UUID originalStatementId = original.getStatementId();

        // Backdate the original's own statement to a prior, already-closed-and-paid period --
        // otherwise it WOULD be "today's" open statement, and the reversal (correctly) landing
        // there would not exercise the "never alter a PAID statement" guarantee this test exists
        // to prove.
        String priorPeriod = YearMonth.now().minusMonths(3).toString();
        backdateAndMarkStatementPaid(originalStatementId, priorPeriod);

        TenantContext.set(tenantId);
        CommissionStatement paidStatementBefore = commissionStatementRepository
            .findByStatementIdAndTenantId(originalStatementId, tenantId).orElseThrow();
        assertThat(paidStatementBefore.getStatus()).isEqualTo(StatementStatus.PAID);
        BigDecimal paidTotalBefore = paidStatementBefore.getTotalAmount();
        long versionBefore = paidStatementBefore.getVersion();

        policyApi.lapsePolicy(policyNumber, "test-staff");

        TenantContext.set(tenantId);
        // The reversal exists and booked into a DIFFERENT statement -- today's fresh OPEN one.
        List<CommissionAccrual> reversals = reversalsOf(tenantId, original.getAccrualId());
        assertThat(reversals).hasSize(1);
        CommissionAccrual reversal = reversals.get(0);
        assertThat(reversal.getStatementId()).isNotEqualTo(originalStatementId);
        CommissionStatement openStatement = commissionStatementRepository
            .findByStatementIdAndTenantId(reversal.getStatementId(), tenantId).orElseThrow();
        assertThat(openStatement.getStatus()).isEqualTo(StatementStatus.OPEN);
        assertThat(openStatement.getPeriod()).isEqualTo(YearMonth.now().toString());
        assertThat(openStatement.getTotalAmount()).isEqualByComparingTo("-10000.00");

        // The PAID statement is completely untouched: same total, same version.
        CommissionStatement paidStatementAfter = commissionStatementRepository
            .findByStatementIdAndTenantId(originalStatementId, tenantId).orElseThrow();
        assertThat(paidStatementAfter.getTotalAmount()).isEqualByComparingTo(paidTotalBefore);
        assertThat(paidStatementAfter.getStatus()).isEqualTo(StatementStatus.PAID);
        assertThat(paidStatementAfter.getVersion()).isEqualTo(versionBefore);
    }

    @Test
    void aFreeLookCancellationClawsBackWithNoWindowAtAll() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "FREELOOK");
        String policyNumber = issuePolicy(tenantId, fixture, new BigDecimal("100000.00"), "FREELOOK-01");

        TenantContext.set(tenantId);
        CommissionAccrual original = originalFirstYearAccrual(tenantId, policyNumber);
        assertThat(original.getAmount()).isEqualByComparingTo("10000.00");

        // 20 months before "now" -- the date at which a LAPSE claws back nothing, because cover
        // genuinely ran for those months and the agent earned something for writing it.
        backdateProjectionIssueDate(tenantId, policyNumber, LocalDate.now().minusMonths(20));

        policyApi.cancelForFreeLook(policyNumber, "fin-2");

        TenantContext.set(tenantId);
        // Free-look undoes the sale FROM INCEPTION, so the window does not apply and the whole
        // accrual goes back. This is the one assertion that distinguishes the two handlers: run it
        // through handlePolicyLapsed instead and it finds no reversal at all.
        List<CommissionAccrual> reversals = reversalsOf(tenantId, original.getAccrualId());
        assertThat(reversals).hasSize(1);
        assertThat(reversals.get(0).getAmount()).isEqualByComparingTo("-10000.00");
        assertThat(reversals.get(0).getAgentId()).isEqualTo(fixture.sellerId());
        assertThat(reversals.get(0).getSourceRef()).isEqualTo(original.getAccrualId().toString());
        // Append-only: the original row is never rewritten.
        assertThat(commissionAccrualRepository.findById(original.getAccrualId()).orElseThrow().getAmount())
            .isEqualByComparingTo("10000.00");
    }

    @Test
    void aLapseForAPolicyWithNoProjectionRowIsASilentNoOp() {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "NOPROJ");
        String policyNumber = issuePolicy(tenantId, fixture, new BigDecimal("100000.00"), "NOPROJ-01");

        TenantContext.set(tenantId);
        CommissionAccrual original = originalFirstYearAccrual(tenantId, policyNumber);

        // Simulates a pre-M7 policy: the projection row this listener itself created is removed,
        // the same "reach an otherwise unreachable state directly via the repository" idiom
        // DistributionApiIntegrationTest already uses.
        policyProjectionRepository.deleteById(new PolicyProjectionId(tenantId, policyNumber));

        // Must not throw, and must not touch the accrual/statement at all.
        policyApi.lapsePolicy(policyNumber, "test-staff");

        TenantContext.set(tenantId);
        assertThat(reversalsOf(tenantId, original.getAccrualId())).isEmpty();
        assertThat(commissionAccrualRepository
            .findByTenantIdAndPolicyNumberAndTierTypeAndReversesAccrualIdIsNull(tenantId, policyNumber, TierType.FIRST_YEAR))
            .hasSize(1);
    }
}
