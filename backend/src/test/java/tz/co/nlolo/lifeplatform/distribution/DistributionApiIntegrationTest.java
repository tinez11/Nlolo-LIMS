package tz.co.nlolo.lifeplatform.distribution;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.distribution.api.AgentNotFoundException;
import tz.co.nlolo.lifeplatform.distribution.api.AgentView;
import tz.co.nlolo.lifeplatform.distribution.api.CommissionPlanNotFoundException;
import tz.co.nlolo.lifeplatform.distribution.api.CommissionPlanView;
import tz.co.nlolo.lifeplatform.distribution.api.DistributionApi;
import tz.co.nlolo.lifeplatform.distribution.api.DistributionValidationException;
import tz.co.nlolo.lifeplatform.distribution.api.LicenseStatus;
import tz.co.nlolo.lifeplatform.distribution.api.PlanStatus;
import tz.co.nlolo.lifeplatform.distribution.api.TierType;
import tz.co.nlolo.lifeplatform.distribution.domain.AgentProfile;
import tz.co.nlolo.lifeplatform.distribution.infrastructure.AgentProfileRepository;
import tz.co.nlolo.lifeplatform.party.api.KycStatus;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
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
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Task 5: {@link DistributionApi}'s full published surface, run under REAL {@code app_role}
 * (NOSUPERUSER NOBYPASSRLS) rather than the Testcontainers superuser -- copies
 * {@code ClaimsApiIntegrationTest}'s {@code @DynamicPropertySource} + {@code ALTER ROLE} setup,
 * the freshest correct example in this codebase, so RLS on {@code distribution}'s four tables is
 * genuinely exercised, not merely declared.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class DistributionApiIntegrationTest {

    private static final String APP_ROLE_PASSWORD = "distribution_it_password";

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
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/refdata/V3__seed_billing_parameters.sql",
            "db-migrations/refdata/V4__seed_distribution_parameters.sql",
            "db-migrations/party/V1__create_party_schema.sql",
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/product/V2__base_rate_table.sql",
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
    @Autowired private AgentProfileRepository agentProfileRepository;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    /** Registers an individual party and immediately verifies its KYC -- onboardAgent's first
     * validation requires exactly this. */
    private PartyView registerVerifiedParty(UUID tenantId, String tag) {
        TenantContext.set(tenantId);
        PartyView party = partyApi.registerIndividual("Distribution IT Agent " + tag, LocalDate.of(1985, 1, 1),
            "+25571" + String.format("%07d", Math.abs(tag.hashCode() % 10000000)), null, "test-agent");
        partyApi.submitKycEvidence(party.partyId(), KycStatus.VERIFIED, "doc-ref-" + tag, "kyc-officer");
        return party;
    }

    /** Creates a product with a published, currently-active version -- createCommissionPlan's
     * first validation (ProductApi.getActiveSnapshot) requires one to exist. */
    private UUID createActiveProduct(UUID tenantId, String productCode) {
        TenantContext.set(tenantId);
        ProductSummaryView product = productApi.createProduct(productCode, "Distribution IT Product " + productCode,
            ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary");
        return product.productId();
    }

    // ---- onboardAgent ------------------------------------------------------------------------

    @Test
    void onboardsAnAgentAndItRoundTripsThroughGetAgent() {
        UUID tenantId = UUID.randomUUID();
        PartyView party = registerVerifiedParty(tenantId, "AGT-ONBOARD-01");

        AgentView view = distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            party.partyId(), "LIC-ONBOARD-01", LocalDate.now().plusYears(1), null), "staff-1");

        assertThat(view.agentId()).isNotNull();
        assertThat(view.partyId()).isEqualTo(party.partyId());
        assertThat(view.licenseNumber()).isEqualTo("LIC-ONBOARD-01");
        assertThat(view.licenseStatus()).isEqualTo(LicenseStatus.ACTIVE);
        assertThat(view.hierarchyParentId()).isNull();

        AgentView reloaded = distributionApi.getAgent(view.agentId());
        assertThat(reloaded.agentId()).isEqualTo(view.agentId());
        assertThat(reloaded.licenseNumber()).isEqualTo("LIC-ONBOARD-01");
    }

    @Test
    void rejectsOnboardingWhenThePartyIsNotKycVerified() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        // Registered but never verified -- stays PENDING.
        PartyView party = partyApi.registerIndividual("Unverified Agent", LocalDate.of(1985, 1, 1),
            "+255715000001", null, "test-agent");

        DistributionApi.OnboardAgentRequest request = new DistributionApi.OnboardAgentRequest(
            party.partyId(), "LIC-KYC-01", LocalDate.now().plusYears(1), null);
        assertThrows(DistributionValidationException.class, () -> distributionApi.onboardAgent(request, "staff-1"));
    }

    @Test
    void rejectsOnboardingWithANonexistentHierarchyParent() {
        UUID tenantId = UUID.randomUUID();
        PartyView party = registerVerifiedParty(tenantId, "AGT-NOPARENT-01");

        DistributionApi.OnboardAgentRequest request = new DistributionApi.OnboardAgentRequest(
            party.partyId(), "LIC-NOPARENT-01", LocalDate.now().plusYears(1), UUID.randomUUID());
        assertThrows(DistributionValidationException.class, () -> distributionApi.onboardAgent(request, "staff-1"));
    }

    @Test
    void rejectsOnboardingWithACrossTenantHierarchyParent() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        PartyView partyA = registerVerifiedParty(tenantA, "AGT-XTENANT-A");
        AgentView parentInTenantA = distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            partyA.partyId(), "LIC-XTENANT-A", LocalDate.now().plusYears(1), null), "staff-1");

        PartyView partyB = registerVerifiedParty(tenantB, "AGT-XTENANT-B");
        TenantContext.set(tenantB);
        // parentInTenantA genuinely exists -- just not in tenant B -- so this must be rejected by
        // the application-layer tenant-scoped lookup, not merely rely on the FK (which RLS would
        // hide, not reject).
        DistributionApi.OnboardAgentRequest request = new DistributionApi.OnboardAgentRequest(
            partyB.partyId(), "LIC-XTENANT-B", LocalDate.now().plusYears(1), parentInTenantA.agentId());
        assertThrows(DistributionValidationException.class, () -> distributionApi.onboardAgent(request, "staff-1"));
    }

    @Test
    void rejectsOnboardingWithAPastLicenseExpiry() {
        UUID tenantId = UUID.randomUUID();
        PartyView party = registerVerifiedParty(tenantId, "AGT-EXPIRED-01");

        DistributionApi.OnboardAgentRequest request = new DistributionApi.OnboardAgentRequest(
            party.partyId(), "LIC-EXPIRED-01", LocalDate.now().minusDays(1), null);
        assertThrows(DistributionValidationException.class, () -> distributionApi.onboardAgent(request, "staff-1"));
    }

    @Test
    void aDuplicateLicenseNumberIsRejectedAsAValidationFailureNotA500() {
        UUID tenantId = UUID.randomUUID();
        PartyView firstParty = registerVerifiedParty(tenantId, "AGT-DUPLIC-A");
        distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            firstParty.partyId(), "LIC-DUPLICATE", LocalDate.now().plusYears(1), null), "staff-1");

        PartyView secondParty = registerVerifiedParty(tenantId, "AGT-DUPLIC-B");
        DistributionApi.OnboardAgentRequest duplicateRequest = new DistributionApi.OnboardAgentRequest(
            secondParty.partyId(), "LIC-DUPLICATE", LocalDate.now().plusYears(1), null);

        // Falsifiable: without the DataIntegrityViolationException catch in
        // DistributionApiImpl.onboardAgent, this would surface ux_agent_license's constraint
        // violation as an uncaught 500 instead of this 422-mapped validation exception.
        assertThrows(DistributionValidationException.class, () -> distributionApi.onboardAgent(duplicateRequest, "staff-1"));
    }

    // ---- resolveAgentTeam ----------------------------------------------------------------------
    //
    // Built for policy/claims' agents-realm "browse my book of business" scoping -- these are the
    // ONLY tests anywhere that exercise resolveAgentTeam against a REAL two-level branching
    // hierarchy through real repository queries (CommissionCalculatorTest's own
    // resolveDescendantIdsCollectsBothLevelsOfABranchingTree pins the pure-function walk itself,
    // with fakes; this pins the real findByTenantIdAndHierarchyParentId wiring around it).

    @Test
    void resolveAgentTeamIncludesSelfAndBothLevelsOfANonLinearDownline() {
        UUID tenantId = UUID.randomUUID();
        PartyView rootParty = registerVerifiedParty(tenantId, "TEAM-ROOT");
        AgentView root = distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            rootParty.partyId(), "LIC-TEAM-ROOT", LocalDate.now().plusYears(1), null), "staff-1");

        PartyView childAParty = registerVerifiedParty(tenantId, "TEAM-CHILD-A");
        AgentView childA = distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            childAParty.partyId(), "LIC-TEAM-CHILD-A", LocalDate.now().plusYears(1), root.agentId()), "staff-1");

        PartyView childBParty = registerVerifiedParty(tenantId, "TEAM-CHILD-B");
        AgentView childB = distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            childBParty.partyId(), "LIC-TEAM-CHILD-B", LocalDate.now().plusYears(1), root.agentId()), "staff-1");

        PartyView grandchildParty = registerVerifiedParty(tenantId, "TEAM-GRANDCHILD");
        AgentView grandchild = distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            grandchildParty.partyId(), "LIC-TEAM-GRANDCHILD", LocalDate.now().plusYears(1), childA.agentId()), "staff-1");

        // A third level -- MUST NOT appear, MAX_HIERARCHY_WALK_DEPTH is 2.
        PartyView tooDeepParty = registerVerifiedParty(tenantId, "TEAM-TOO-DEEP");
        distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            tooDeepParty.partyId(), "LIC-TEAM-TOO-DEEP", LocalDate.now().plusYears(1), grandchild.agentId()), "staff-1");

        TenantContext.set(tenantId);
        List<UUID> team = distributionApi.resolveAgentTeam(rootParty.partyId());

        assertThat(team).containsExactlyInAnyOrder(root.agentId(), childA.agentId(), childB.agentId(), grandchild.agentId());
    }

    @Test
    void resolveAgentTeamIsJustSelfForALeafAgent() {
        UUID tenantId = UUID.randomUUID();
        PartyView leafParty = registerVerifiedParty(tenantId, "TEAM-LEAF");
        AgentView leaf = distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            leafParty.partyId(), "LIC-TEAM-LEAF", LocalDate.now().plusYears(1), null), "staff-1");

        TenantContext.set(tenantId);
        assertThat(distributionApi.resolveAgentTeam(leafParty.partyId())).containsExactly(leaf.agentId());
    }

    @Test
    void resolveAgentTeamIsEmptyWhenThePartyIsNotAnAgent() {
        UUID tenantId = UUID.randomUUID();
        // A real, verified party that was never onboarded as an agent at all.
        PartyView notAnAgent = registerVerifiedParty(tenantId, "TEAM-NOT-AN-AGENT");

        TenantContext.set(tenantId);
        assertThat(distributionApi.resolveAgentTeam(notAnAgent.partyId())).isEmpty();
    }

    @Test
    void resolveAgentTeamDoesNotCrossTenants() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        PartyView partyInTenantA = registerVerifiedParty(tenantA, "TEAM-XTENANT-A");
        distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            partyInTenantA.partyId(), "LIC-TEAM-XTENANT-A", LocalDate.now().plusYears(1), null), "staff-1");

        // Same partyId looked up under the WRONG tenant's context -- RLS/tenant-scoping must
        // treat this as "not an agent here", not accidentally find tenant A's row.
        TenantContext.set(tenantB);
        assertThat(distributionApi.resolveAgentTeam(partyInTenantA.partyId())).isEmpty();
    }

    // ---- createCommissionPlan -----------------------------------------------------------------

    /**
     * A FLAT rule is a first-class half of the rate-XOR-flat invariant, and until this test no
     * test anywhere persisted one -- the only {@code flatAmount} in the suite was the NEGATIVE
     * case asserting that rate+flat together is rejected. So the whole flat branch (V2's
     * {@code commission_rule_rate_xor_flat} and {@code commission_rule_flat_currency_paired}
     * CHECKs, the CHAR(3) {@code flat_currency} column, and the view mapping) had never once run
     * against a database. Added after Task 8 found a payout path that could never have worked for
     * exactly this reason.
     */
    @Test
    void aFlatAmountRulePersistsAndRoundTripsThroughGetApplicablePlan() {
        UUID tenantId = UUID.randomUUID();
        UUID productId = createActiveProduct(tenantId, "DIST-IT-FLAT");
        PartyView party = registerVerifiedParty(tenantId, "AGT-FLAT-01");
        AgentView agent = distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            party.partyId(), "LIC-FLAT-01", LocalDate.now().plusYears(1), null), "staff-1");

        distributionApi.createCommissionPlan(productId, List.of(
            new DistributionApi.CommissionRuleInput(TierType.FIRST_YEAR, null, new BigDecimal("25000.00"), "TZS")),
            "actuary");

        CommissionPlanView plan = distributionApi.getApplicablePlan(agent.agentId(), productId);
        assertThat(plan.rules()).hasSize(1);
        assertThat(plan.rules().get(0).tierType()).isEqualTo(TierType.FIRST_YEAR);
        assertThat(plan.rules().get(0).rate()).isNull();
        assertThat(plan.rules().get(0).flatAmount()).isEqualByComparingTo("25000.00");
        // CHAR(3) -- a bpchar round-trip that silently pads would surface right here.
        assertThat(plan.rules().get(0).flatCurrency()).isEqualTo("TZS");
    }

    @Test
    void createCommissionPlanRejectsARuleWithNeitherARateNorAFlatAmount() {
        UUID tenantId = UUID.randomUUID();
        UUID productId = createActiveProduct(tenantId, "DIST-IT-NEITHER");

        // The other half of the XOR. V1 left both columns nullable with no constraint, so such a
        // rule was insertable and would silently accrue nothing forever -- V2 added the CHECK, and
        // the application layer should reject it before the database has to.
        List<DistributionApi.CommissionRuleInput> rules = List.of(
            new DistributionApi.CommissionRuleInput(TierType.FIRST_YEAR, null, null, null));
        assertThrows(DistributionValidationException.class,
            () -> distributionApi.createCommissionPlan(productId, rules, "actuary"));
    }

    @Test
    void createCommissionPlanRejectsAThresholdBonusRule() {
        UUID tenantId = UUID.randomUUID();
        UUID productId = createActiveProduct(tenantId, "DIST-IT-THRESH-01");

        List<DistributionApi.CommissionRuleInput> rules = List.of(
            new DistributionApi.CommissionRuleInput(TierType.THRESHOLD_BONUS, new BigDecimal("0.05"), null, null));

        // Falsifiable: THRESHOLD_BONUS is in the CHECK-allowed set and in the TierType enum, so
        // without CommissionRule's constructor guard (M7 user decision 1) this would insert fine.
        assertThrows(DistributionValidationException.class,
            () -> distributionApi.createCommissionPlan(productId, rules, "actuary"));
    }

    @Test
    void createCommissionPlanRejectsARuleWithBothRateAndFlatAmount() {
        UUID tenantId = UUID.randomUUID();
        UUID productId = createActiveProduct(tenantId, "DIST-IT-XOR-01");

        List<DistributionApi.CommissionRuleInput> rules = List.of(
            new DistributionApi.CommissionRuleInput(TierType.FIRST_YEAR, new BigDecimal("0.05"),
                new BigDecimal("1000"), "TZS"));

        assertThrows(DistributionValidationException.class,
            () -> distributionApi.createCommissionPlan(productId, rules, "actuary"));
    }

    @Test
    void createCommissionPlanRejectsAnEmptyRuleList() {
        UUID tenantId = UUID.randomUUID();
        UUID productId = createActiveProduct(tenantId, "DIST-IT-EMPTY-01");

        assertThrows(DistributionValidationException.class,
            () -> distributionApi.createCommissionPlan(productId, List.of(), "actuary"));
    }

    // ---- getApplicablePlan ---------------------------------------------------------------------

    @Test
    void getApplicablePlanFallsBackToTheProductsActivePlanWhenTheAgentHasNoneAssigned() {
        UUID tenantId = UUID.randomUUID();
        UUID productId = createActiveProduct(tenantId, "DIST-IT-FALLBACK-01");
        PartyView party = registerVerifiedParty(tenantId, "AGT-FALLBACK-01");
        AgentView agent = distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            party.partyId(), "LIC-FALLBACK-01", LocalDate.now().plusYears(1), null), "staff-1");

        CommissionPlanView createdPlan = distributionApi.createCommissionPlan(productId,
            List.of(new DistributionApi.CommissionRuleInput(TierType.FIRST_YEAR, new BigDecimal("0.05"), null, null)),
            "actuary");

        CommissionPlanView resolved = distributionApi.getApplicablePlan(agent.agentId(), productId);

        assertThat(resolved.commissionPlanId()).isEqualTo(createdPlan.commissionPlanId());
        assertThat(resolved.status()).isEqualTo(PlanStatus.ACTIVE);
        assertThat(resolved.rules()).hasSize(1);
        assertThat(resolved.rules().get(0).tierType()).isEqualTo(TierType.FIRST_YEAR);
    }

    @Test
    void getApplicablePlanResolvesTheAgentsOwnAssignedPlanOverTheProductFallback() {
        UUID tenantId = UUID.randomUUID();
        UUID productId = createActiveProduct(tenantId, "DIST-IT-OWNPLAN-01");
        PartyView party = registerVerifiedParty(tenantId, "AGT-OWNPLAN-01");
        AgentView agentView = distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            party.partyId(), "LIC-OWNPLAN-01", LocalDate.now().plusYears(1), null), "staff-1");

        // The tenant's ordinary ACTIVE plan for the product -- would be returned by the fallback
        // path alone.
        distributionApi.createCommissionPlan(productId,
            List.of(new DistributionApi.CommissionRuleInput(TierType.FIRST_YEAR, new BigDecimal("0.05"), null, null)),
            "actuary");

        // A second, distinct plan assigned directly to this agent.
        CommissionPlanView ownPlan = distributionApi.createCommissionPlan(productId,
            List.of(new DistributionApi.CommissionRuleInput(TierType.RENEWAL, new BigDecimal("0.02"), null, null)),
            "actuary");

        // DistributionApi has no method to assign a plan to an agent -- that wiring is out of this
        // task's scope -- so reach the otherwise-unreachable state directly via the repository,
        // the same pattern ClaimsApiIntegrationTest.settleDirectly uses for a state its own API
        // cannot drive to.
        TenantContext.set(tenantId);
        AgentProfile agent = agentProfileRepository.findByAgentIdAndTenantId(agentView.agentId(), tenantId).orElseThrow();
        agent.setCommissionPlanId(ownPlan.commissionPlanId());
        agentProfileRepository.save(agent);

        CommissionPlanView resolved = distributionApi.getApplicablePlan(agentView.agentId(), productId);

        // Falsifiable: if the agent's own assigned plan were not preferred, this would resolve to
        // the first-created (FIRST_YEAR) plan instead.
        assertThat(resolved.commissionPlanId()).isEqualTo(ownPlan.commissionPlanId());
        assertThat(resolved.rules()).hasSize(1);
        assertThat(resolved.rules().get(0).tierType()).isEqualTo(TierType.RENEWAL);
    }

    @Test
    void getApplicablePlanThrowsWhenNeitherTheAgentNorTheProductHasAPlan() {
        UUID tenantId = UUID.randomUUID();
        UUID productId = createActiveProduct(tenantId, "DIST-IT-NOPLAN-01");
        PartyView party = registerVerifiedParty(tenantId, "AGT-NOPLAN-01");
        AgentView agent = distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            party.partyId(), "LIC-NOPLAN-01", LocalDate.now().plusYears(1), null), "staff-1");

        assertThrows(CommissionPlanNotFoundException.class,
            () -> distributionApi.getApplicablePlan(agent.agentId(), productId));
    }

    // ---- tenant isolation under real RLS -------------------------------------------------------

    @Test
    void anAgentIsInvisibleToAnyOtherTenantUnderRealRls() {
        UUID ownerTenant = UUID.randomUUID();
        UUID otherTenant = UUID.randomUUID();
        PartyView party = registerVerifiedParty(ownerTenant, "AGT-TENANT-01");
        AgentView agent = distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            party.partyId(), "LIC-TENANT-01", LocalDate.now().plusYears(1), null), "staff-1");

        // Same agentId, DIFFERENT tenant context -- must come back not-found, proving
        // AgentProfileRepository.findByAgentIdAndTenantId is genuinely enforced by Postgres RLS
        // under real app_role (NOSUPERUSER NOBYPASSRLS), not merely by the tenantId argument the
        // application layer happens to pass.
        TenantContext.set(otherTenant);
        assertThrows(AgentNotFoundException.class, () -> distributionApi.getAgent(agent.agentId()));

        // Sanity check: the SAME tenant can still read it, so the not-found above is genuinely
        // about tenant isolation and not some other bug (e.g. a broken agentId).
        TenantContext.set(ownerTenant);
        assertThat(distributionApi.getAgent(agent.agentId()).agentId()).isEqualTo(agent.agentId());
    }
}
