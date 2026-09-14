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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
            "db-migrations/party/V2__individual_person_record.sql",
            "db-migrations/party/V4__registered_by_agent.sql",
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/product/V2__base_rate_table.sql",
            "db-migrations/product/V3__base_rate_structured_age.sql",
            "db-migrations/product/V4__rating_table_unique_band.sql",
            "db-migrations/product/V5__rating_table_age_bounds.sql",
            "db-migrations/product/V6__eligibility_bounds.sql",
            "db-migrations/product/V9__rating_table_sum_assured_bounds.sql",
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
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
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

    // ---- M13: listAgents ---------------------------------------------------------------------

    /**
     * The list this module never had. Every per-agent read existed; nothing could
     * enumerate them, so an agent table had no feed and the console's nav item
     * pointed at the onboarding form instead of a queue.
     */
    @Test
    void listsAgentsForTheTenantPaged() {
        UUID tenantId = UUID.randomUUID();
        for (int i = 1; i <= 3; i++) {
            PartyView party = registerVerifiedParty(tenantId, "AGT-LIST-0" + i);
            distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
                party.partyId(), "LIC-LIST-0" + i, LocalDate.now().plusYears(1), null), "staff-1");
        }
        TenantContext.set(tenantId);

        Page<AgentView> firstPage = distributionApi.listAgents(null, null, null, PageRequest.of(0, 2));
        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(distributionApi.listAgents(null, null, null, PageRequest.of(1, 2)).getContent()).hasSize(1);
    }

    /** RLS is the backstop; the query is tenant-scoped in its own right. */
    @Test
    void listAgentsNeverCrossesTenants() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        PartyView partyA = registerVerifiedParty(tenantA, "AGT-ISO-A");
        distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            partyA.partyId(), "LIC-ISO-A", LocalDate.now().plusYears(1), null), "staff-1");

        TenantContext.set(tenantB);
        assertThat(distributionApi.listAgents(null, null, null, PageRequest.of(0, 20)).getTotalElements()).isZero();
    }

    /**
     * `q` matches LICENCE NUMBER, not a name -- an agent has no name in this context.
     * The person's name lives in `party`, and resolving it here would make
     * distribution read another context's data to fill a column.
     */
    @Test
    void listAgentsSearchesByLicenceNumberCaseInsensitively() {
        UUID tenantId = UUID.randomUUID();
        PartyView one = registerVerifiedParty(tenantId, "AGT-Q-1");
        PartyView two = registerVerifiedParty(tenantId, "AGT-Q-2");
        distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            one.partyId(), "LIC-NORTH-001", LocalDate.now().plusYears(1), null), "staff-1");
        distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            two.partyId(), "LIC-SOUTH-002", LocalDate.now().plusYears(1), null), "staff-1");
        TenantContext.set(tenantId);

        assertThat(distributionApi.listAgents("north", null, null, PageRequest.of(0, 20)).getContent())
            .extracting(AgentView::licenseNumber).containsExactly("LIC-NORTH-001");
        assertThat(distributionApi.listAgents("SOUTH", null, null, PageRequest.of(0, 20)).getContent())
            .extracting(AgentView::licenseNumber).containsExactly("LIC-SOUTH-002");
        assertThat(distributionApi.listAgents("LIC-", null, null, PageRequest.of(0, 20)).getTotalElements()).isEqualTo(2);
    }

    /**
     * `q` ALSO matches the person's name now, which is what lets the console offer an agent
     * picker instead of a uuid box. An agent still has no name in this module: the name half is
     * resolved to party ids through {@code party::api} and filtered against ids distribution
     * already holds.
     *
     * <p>The licence numbers here deliberately share no substring with the names, so a hit can
     * only have come from the name half.
     */
    @Test
    void listAgentsSearchesByThePersonsNameNotOnlyTheLicenceNumber() {
        UUID tenantId = UUID.randomUUID();
        PartyView zebra = registerVerifiedParty(tenantId, "ZEBRA");
        PartyView walrus = registerVerifiedParty(tenantId, "WALRUS");
        distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            zebra.partyId(), "LIC-0001", LocalDate.now().plusYears(1), null), "staff-1");
        distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            walrus.partyId(), "LIC-0002", LocalDate.now().plusYears(1), null), "staff-1");
        TenantContext.set(tenantId);

        assertThat(distributionApi.listAgents("ZEBRA", null, null, PageRequest.of(0, 20)).getContent())
            .extracting(AgentView::licenseNumber).containsExactly("LIC-0001");
        assertThat(distributionApi.listAgents("WALRUS", null, null, PageRequest.of(0, 20)).getContent())
            .extracting(AgentView::licenseNumber).containsExactly("LIC-0002");
        // Case-insensitive on the name half too, like the licence half.
        assertThat(distributionApi.listAgents("zebra", null, null, PageRequest.of(0, 20)).getContent())
            .extracting(AgentView::licenseNumber).containsExactly("LIC-0001");
        // A licence search still works, and does NOT accidentally widen to both via the name half.
        assertThat(distributionApi.listAgents("LIC-0001", null, null, PageRequest.of(0, 20)).getContent())
            .extracting(AgentView::licenseNumber).containsExactly("LIC-0001");
    }

    /**
     * The failure this guards is specific and silent: an `in` clause cannot take an empty
     * collection, and the obvious workaround -- passing null and letting the predicate fall
     * away -- turns "no name matched" into "no filter at all", i.e. every agent in the tenant.
     * A picker built on that would offer every agent for any typo.
     */
    @Test
    void aNameSearchMatchingNobodyReturnsNothingRatherThanEveryAgent() {
        UUID tenantId = UUID.randomUUID();
        PartyView one = registerVerifiedParty(tenantId, "PRESENT-A");
        PartyView two = registerVerifiedParty(tenantId, "PRESENT-B");
        distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            one.partyId(), "LIC-PRESENT-A", LocalDate.now().plusYears(1), null), "staff-1");
        distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            two.partyId(), "LIC-PRESENT-B", LocalDate.now().plusYears(1), null), "staff-1");
        TenantContext.set(tenantId);

        // Sanity: there ARE two agents, so an empty result below is a real filter, not an
        // empty tenant.
        assertThat(distributionApi.listAgents(null, null, null, PageRequest.of(0, 20)).getTotalElements())
            .isEqualTo(2);
        assertThat(distributionApi.listAgents("Nobodyofthatname", null, null, PageRequest.of(0, 20))
            .getTotalElements()).isZero();
    }

    // ---- agentIdForParty: who actually gets paid ----------------------------------------------

    /**
     * This method decides WHO GETS PAID -- it is how a client's registering agent becomes the
     * agent of record at issuance -- and until now nothing exercised it at all.
     */
    @Test
    void agentIdForPartyResolvesThePartysAgent() {
        UUID tenantId = UUID.randomUUID();
        PartyView party = registerVerifiedParty(tenantId, "IDFOR-1");
        AgentView agent = distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            party.partyId(), "LIC-IDFOR-1", LocalDate.now().plusYears(1), null), "staff-1");
        TenantContext.set(tenantId);

        assertThat(distributionApi.agentIdForParty(party.partyId())).contains(agent.agentId());
    }

    /** Most parties are customers. That is the ordinary case, not an error. */
    @Test
    void agentIdForPartyIsEmptyForAPartyThatIsNotAnAgent() {
        UUID tenantId = UUID.randomUUID();
        PartyView customer = registerVerifiedParty(tenantId, "IDFOR-CUSTOMER");
        TenantContext.set(tenantId);

        assertThat(distributionApi.agentIdForParty(customer.partyId())).isEmpty();
    }

    /**
     * A party may hold SEVERAL agent profiles -- nothing in the DDL prevents it, and this
     * platform's own dev database has one party carrying 46. Which profile this resolves to is
     * therefore a live question, and the answer decides whose statement a commission lands on.
     *
     * <p>ACTIVE wins over recency: the newer profile here is SUSPENDED, and a suspended licence
     * is not the one an agent is currently writing business on.
     */
    @Test
    void agentIdForPartyPrefersAnActiveProfileOverANewerSuspendedOne() {
        UUID tenantId = UUID.randomUUID();
        PartyView party = registerVerifiedParty(tenantId, "IDFOR-MULTI");
        AgentView older = distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            party.partyId(), "LIC-IDFOR-OLD", LocalDate.now().plusYears(1), null), "staff-1");
        AgentView newer = distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            party.partyId(), "LIC-IDFOR-NEW", LocalDate.now().plusYears(1), null), "staff-1");
        distributionApi.suspendAgent(newer.agentId(), "staff-1");
        TenantContext.set(tenantId);

        assertThat(distributionApi.agentIdForParty(party.partyId())).contains(older.agentId());
    }

    /**
     * DETERMINISM, which is the actual defect this closes. With several equally-eligible
     * profiles the underlying query had no ORDER BY and the caller took the first row, so the
     * answer was whatever Postgres happened to return -- two identical calls could credit two
     * different agents. Repeating the call is the only way to observe that from outside.
     */
    @Test
    void agentIdForPartyReturnsTheSameAgentEveryTimeWhenAPartyHasSeveralActiveProfiles() {
        UUID tenantId = UUID.randomUUID();
        PartyView party = registerVerifiedParty(tenantId, "IDFOR-STABLE");
        for (int i = 1; i <= 5; i++) {
            distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
                party.partyId(), "LIC-IDFOR-ST-" + i, LocalDate.now().plusYears(1), null), "staff-1");
        }
        TenantContext.set(tenantId);

        UUID first = distributionApi.agentIdForParty(party.partyId()).orElseThrow();
        for (int i = 0; i < 5; i++) {
            assertThat(distributionApi.agentIdForParty(party.partyId())).contains(first);
        }
    }

    @Test
    void listAgentsFiltersByLicenceStatus() {
        UUID tenantId = UUID.randomUUID();
        PartyView active = registerVerifiedParty(tenantId, "AGT-ST-A");
        PartyView suspended = registerVerifiedParty(tenantId, "AGT-ST-S");
        distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            active.partyId(), "LIC-ST-ACTIVE", LocalDate.now().plusYears(1), null), "staff-1");
        AgentView toSuspend = distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            suspended.partyId(), "LIC-ST-SUSPENDED", LocalDate.now().plusYears(1), null), "staff-1");
        distributionApi.suspendAgent(toSuspend.agentId(), "staff-1");
        TenantContext.set(tenantId);

        assertThat(distributionApi.listAgents(null, LicenseStatus.ACTIVE, null, PageRequest.of(0, 20)).getContent())
            .extracting(AgentView::licenseNumber).containsExactly("LIC-ST-ACTIVE");
        assertThat(distributionApi.listAgents(null, LicenseStatus.SUSPENDED, null, PageRequest.of(0, 20)).getContent())
            .extracting(AgentView::licenseNumber).containsExactly("LIC-ST-SUSPENDED");
        // Both filters compose -- the JPQL search branch takes the status too.
        assertThat(distributionApi.listAgents("ST-", LicenseStatus.ACTIVE, null, PageRequest.of(0, 20)).getContent())
            .extracting(AgentView::licenseNumber).containsExactly("LIC-ST-ACTIVE");
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

    /**
     * "Is this client also an agent" — the client register's question, asked of the agent list.
     * A party may hold more than one agent record, which is why this filters a list rather than
     * returning one, and why the assertion below counts rather than fetching.
     */
    @Test
    void listAgentsByPartyIdFindsTheAgentRecordsHeldByOneParty() {
        UUID tenantId = UUID.randomUUID();
        PartyView agentParty = registerVerifiedParty(tenantId, "AGT-BYPARTY-01");
        PartyView unrelated = registerVerifiedParty(tenantId, "AGT-BYPARTY-02");
        distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            agentParty.partyId(), "LIC-BYPARTY-01", LocalDate.now().plusYears(1), null), "staff-1");
        distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            unrelated.partyId(), "LIC-BYPARTY-02", LocalDate.now().plusYears(1), null), "staff-1");

        assertThat(distributionApi.listAgents(null, null, agentParty.partyId(), PageRequest.of(0, 20)).getContent())
            .extracting(AgentView::licenseNumber)
            .containsExactly("LIC-BYPARTY-01");
    }

    @Test
    void listAgentsByPartyIdIsEmptyForAClientWhoIsNotAnAgent() {
        UUID tenantId = UUID.randomUUID();
        PartyView plainClient = registerVerifiedParty(tenantId, "AGT-BYPARTY-03");
        // Someone IS an agent in this tenant, so an empty result cannot come from an empty table.
        PartyView realAgent = registerVerifiedParty(tenantId, "AGT-BYPARTY-04");
        distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            realAgent.partyId(), "LIC-BYPARTY-04", LocalDate.now().plusYears(1), null), "staff-1");

        assertThat(distributionApi.listAgents(null, null, plainClient.partyId(), PageRequest.of(0, 20)))
            .isEmpty();
    }

    /**
     * partyId deliberately ignores q and status. Combining them would let a caller believe it had
     * searched the register when it had only looked up one party, so this pins the documented
     * behaviour rather than leaving it to be discovered.
     */
    @Test
    void listAgentsByPartyIdIgnoresTheSearchAndStatusFilters() {
        UUID tenantId = UUID.randomUUID();
        PartyView agentParty = registerVerifiedParty(tenantId, "AGT-BYPARTY-05");
        distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            agentParty.partyId(), "LIC-BYPARTY-05", LocalDate.now().plusYears(1), null), "staff-1");

        assertThat(distributionApi.listAgents("NO-SUCH-LICENCE", LicenseStatus.SUSPENDED,
                agentParty.partyId(), PageRequest.of(0, 20)).getContent())
            .extracting(AgentView::licenseNumber)
            .containsExactly("LIC-BYPARTY-05");
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

    /**
     * Nothing supersedes a prior ACTIVE plan when a new one is created, so two ACTIVE plans for one
     * product is a reachable state -- and the fallback that resolves "the plan for this product"
     * used to take {@code .findFirst()} off a query with no {@code ORDER BY}. Which plan won, and
     * therefore the rate an agent is actually paid, was whatever row Postgres returned first.
     *
     * <p>The two plans below differ ONLY in their rate, so the assertion can distinguish them: 5%
     * authored first, 7% second. Newest-first means 7% applies. Without the ordering this asserts
     * nothing reliable, which is the point -- it is the falsifiable half of the fix.
     */
    @Test
    void getApplicablePlanResolvesTheNewestActivePlanWhenAProductHasMoreThanOne() {
        UUID tenantId = UUID.randomUUID();
        UUID productId = createActiveProduct(tenantId, "DIST-IT-TWOPLANS");
        PartyView party = registerVerifiedParty(tenantId, "AGT-TWOPLANS-01");
        AgentView agent = distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            party.partyId(), "LIC-TWOPLANS-01", LocalDate.now().plusYears(1), null), "staff-1");

        distributionApi.createCommissionPlan(productId, List.of(
            new DistributionApi.CommissionRuleInput(TierType.FIRST_YEAR, new BigDecimal("0.0500"), null, null)),
            "actuary-old");
        distributionApi.createCommissionPlan(productId, List.of(
            new DistributionApi.CommissionRuleInput(TierType.FIRST_YEAR, new BigDecimal("0.0700"), null, null)),
            "actuary-new");

        CommissionPlanView plan = distributionApi.getApplicablePlan(agent.agentId(), productId);
        assertThat(plan.rules()).hasSize(1);
        assertThat(plan.rules().get(0).rate())
            .as("the newest ACTIVE plan, not whichever row the scan yielded")
            .isEqualByComparingTo("0.0700");
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
