package tz.co.nlolo.lifeplatform.distribution;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.distribution.api.AgentNotFoundException;
import tz.co.nlolo.lifeplatform.distribution.api.AgentView;
import tz.co.nlolo.lifeplatform.distribution.api.CommissionAccrualView;
import tz.co.nlolo.lifeplatform.distribution.api.CommissionStatementNotFoundException;
import tz.co.nlolo.lifeplatform.distribution.api.CommissionStatementView;
import tz.co.nlolo.lifeplatform.distribution.api.DistributionApi;
import tz.co.nlolo.lifeplatform.distribution.api.StatementStatus;
import tz.co.nlolo.lifeplatform.distribution.api.TierType;
import tz.co.nlolo.lifeplatform.distribution.domain.CommissionAccrual;
import tz.co.nlolo.lifeplatform.distribution.domain.CommissionStatement;
import tz.co.nlolo.lifeplatform.distribution.domain.PolicyProjection;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;
import tz.co.nlolo.lifeplatform.product.api.BenefitCalculationMethod;

/**
 * Task 6, Step 4 -- the real end-to-end proof that issuing a policy through the actual
 * {@code PolicyApi} (not a hand-built event) reaches {@code distribution.application
 * .PolicyEventListener.handlePolicyIssued} and produces real rows: {@code policy_projection},
 * the seller's {@code FIRST_YEAR} accrual, and both ancestors' {@code OVERRIDE}/{@code
 * SUPERVISOR_OVERRIDE} accruals, each rolled into a real {@code commission_statement} whose
 * {@code total_amount} is read back from the database, never inferred from an event.
 *
 * <p>Runs against real Postgres as {@code app_role} (NOSUPERUSER NOBYPASSRLS), copying {@code
 * DistributionApiIntegrationTest}/{@code ClaimSettlementEndToEndTest}'s bootstrap, so RLS on every
 * table this chain touches is genuinely exercised.
 *
 * <p>{@code @TransactionalEventListener(phase = AFTER_COMMIT)} chains are synchronous, same
 * thread: {@code PolicyApiImpl.issuePolicy}'s own {@code @Transactional} commits when the call
 * returns, firing {@code PolicyEventListener} synchronously before control comes back to this
 * test's caller, whose own single {@code REQUIRES_NEW} transaction commits before {@code
 * issuePolicy} itself returns. No await/sleep anywhere in this class.
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class CommissionAccrualEndToEndTest {

    private static final String APP_ROLE_PASSWORD = "commission_accrual_e2e_password";
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
            "db-migrations/refdata/V8__ifrs17_branches_and_channels.sql",
            "db-migrations/refdata/V9__journal_reason_codes.sql",
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
            "db-migrations/underwriting/V20__sale_lock_backfill.sql",
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
            "db-migrations/distribution/V1__create_distribution_schema.sql",
            "db-migrations/distribution/V2__grants_rls_money_checks_projection_and_statement_lifecycle.sql",
            "db-migrations/distribution/V5__agent_channel_and_home_branch.sql",
            "db-migrations/distribution/V6__commission_withholding.sql");
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
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private TransactionTemplate transactionTemplate() {
        if (transactionTemplate == null) {
            transactionTemplate = new TransactionTemplate(transactionManager);
        }
        return transactionTemplate;
    }

    private record Hierarchy(UUID grandparentId, UUID parentId, UUID sellerId, UUID productId, UUID productVersionId) {}

    /** A 3-level agent hierarchy (grandparent -> parent -> seller) sharing ONE commission plan on
     * ONE product, with a rule for each of the three tiers this test needs. Every agent falls back
     * to the SAME plan (none has an explicit {@code commissionPlanId} of its own) -- {@link
     * tz.co.nlolo.lifeplatform.distribution.application.DistributionApiImpl#resolveAgentWithPlan}
     * still resolves it independently per agent, they just happen to land on the same row, which
     * is a legitimate, simpler setup than three near-identical plans. */
    private Hierarchy buildHierarchy(UUID tenantId, String tag) {
        TenantContext.set(tenantId);
        ProductSummaryView product = productApi.createProduct("DIST-E2E-" + tag, "Distribution E2E Product " + tag,
            ProductCategory.TERM_LIFE, CURRENCY, "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary");
        UUID productVersionId = productApi.getActiveSnapshot(product.productId(), LocalDate.now()).productVersionId();

        distributionApi.createCommissionPlan(product.productId(), List.of(
            new DistributionApi.CommissionRuleInput(TierType.FIRST_YEAR, new BigDecimal("0.10"), null, null),
            new DistributionApi.CommissionRuleInput(TierType.RENEWAL, new BigDecimal("0.03"), null, null),
            new DistributionApi.CommissionRuleInput(TierType.OVERRIDE, new BigDecimal("0.05"), null, null),
            new DistributionApi.CommissionRuleInput(TierType.SUPERVISOR_OVERRIDE, new BigDecimal("0.02"), null, null)),
            "actuary");

        UUID grandparentId = onboardAgent(tenantId, tag + "-GP", null);
        UUID parentId = onboardAgent(tenantId, tag + "-P", grandparentId);
        UUID sellerId = onboardAgent(tenantId, tag + "-S", parentId);
        return new Hierarchy(grandparentId, parentId, sellerId, product.productId(), productVersionId);
    }

    private UUID onboardAgent(UUID tenantId, String tag, UUID parentId) {
        TenantContext.set(tenantId);
        PartyView party = partyApi.registerIndividual("Distribution E2E Agent " + tag, LocalDate.of(1985, 1, 1),
            "+25571" + String.format("%07d", Math.abs(tag.hashCode() % 10000000)), null, "test-agent");
        partyApi.submitKycEvidence(party.partyId(), tz.co.nlolo.lifeplatform.party.api.KycStatus.VERIFIED,
            "doc-ref-" + tag, "kyc-officer");
        return distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            party.partyId(), "LIC-" + tag, LocalDate.now().plusYears(1), parentId), "staff-1").agentId();
    }

    /**
     * An offer: issued, but with no premium yet collected, so no cover and no commission.
     *
     * <p>Split out of {@link #issuePolicy} when commission moved from {@code PolicyIssued} to
     * {@code PolicyActivated}. Every test here but the "not before cover" one wants a policy
     * genuinely on risk, so they go through {@code issuePolicy}, which pays.
     */
    private String issueOffer(UUID tenantId, Hierarchy hierarchy, UUID agentOfRecordId, BigDecimal premium, String tag) {
        TenantContext.set(tenantId);
        PartyView policyholder = partyApi.registerIndividual("Distribution E2E Policyholder " + tag,
            LocalDate.of(1980, 6, 1), "+25572" + String.format("%07d", Math.abs(tag.hashCode() % 10000000)), null, "test-agent");
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(policyholder.partyId(), hierarchy.productId(),
            hierarchy.productVersionId(), new BigDecimal("2000000"), CURRENCY, premium, CURRENCY, "MONTHLY",
            agentOfRecordId, List.of(), "Distribution E2E test");
        return policyApi.issuePolicy(null, request, "test-staff").policyNumber();
    }

    private String issuePolicy(UUID tenantId, Hierarchy hierarchy, UUID agentOfRecordId, BigDecimal premium, String tag) {
        String policyNumber = issueOffer(tenantId, hierarchy, agentOfRecordId, premium, tag);
        // The first premium is what starts cover, and cover is what earns commission. Without
        // this the policy stays an offer and every assertion below correctly finds nothing.
        TenantContext.set(tenantId);
        policyApi.activateOnFirstPremium(policyNumber);
        return policyNumber;
    }

    /**
     * THE AGENT WHO REGISTERED THE CUSTOMER EARNS THE COMMISSION, WITHOUT ANYONE NAMING THEM.
     *
     * <p>This is the whole point of the binding, and it is asserted end to end — register a
     * customer as an agent, issue a policy that names NOBODY, collect the first premium, and the
     * registering agent is owed money.
     *
     * <p>What it replaces: commission accrues off {@code PolicyActivated.agentOfRecordId}, which
     * used to be only whatever the form carried. "Who registered this client" WAS recorded — as
     * the registering user's Keycloak subject in {@code created_by} — but nothing could resolve a
     * subject to an agent, so an agent could sign a customer up and earn nothing on their
     * policies unless somebody separately named them on every single case. Every test above this
     * one passes {@code hierarchy.sellerId()} by hand, which is exactly the step real life
     * forgets.
     *
     * <p>Deliberately passes a NULL agent of record. Before the bind that produced a direct sale
     * and zero accruals, which is the assertion one test further down. The only difference here
     * is that the policyholder was registered BY an agent.
     */
    @Test
    void commissionGoesToTheAgentWhoRegisteredTheCustomerEvenWhenNobodyNamesThem() {
        UUID tenantId = UUID.randomUUID();
        Hierarchy hierarchy = buildHierarchy(tenantId, "REGISTRAR");
        BigDecimal premium = new BigDecimal("80000.00");

        // The seller's own party -- the identity an agent's token carries as its party_id claim,
        // and therefore what the console records when that agent registers a customer.
        TenantContext.set(tenantId);
        UUID sellerPartyId = distributionApi.getAgent(hierarchy.sellerId()).partyId();

        PartyView policyholder = partyApi.registerIndividual(
            new tz.co.nlolo.lifeplatform.party.api.IndividualRegistration(
                "Registered By An Agent", LocalDate.of(1980, 6, 1), "+255729900001", null,
                null, null, tz.co.nlolo.lifeplatform.party.api.IdentityDocument.none(),
                null, null, null, null, tz.co.nlolo.lifeplatform.party.api.Address.none()),
            "agent-user-subject", sellerPartyId);

        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(policyholder.partyId(),
            hierarchy.productId(), hierarchy.productVersionId(), new BigDecimal("2000000"), CURRENCY,
            premium, CURRENCY, "MONTHLY",
            // NOBODY NAMED. This is the case that used to pay nothing at all.
            null, List.of(), "Distribution E2E test");
        String policyNumber = policyApi.issuePolicy(null, request, "test-staff").policyNumber();
        TenantContext.set(tenantId);
        policyApi.activateOnFirstPremium(policyNumber);

        TenantContext.set(tenantId);
        List<CommissionAccrual> firstYear = commissionAccrualRepository
            .findByTenantIdAndPolicyNumberAndTierTypeAndReversesAccrualIdIsNull(
                tenantId, policyNumber, TierType.FIRST_YEAR);
        assertThat(firstYear)
            .as("an agent who registers a customer must earn on that customer's policy")
            .hasSize(1);
        assertThat(firstYear.get(0).getAgentId())
            .as("and the first-year commission belongs to the agent who registered them")
            .isEqualTo(hierarchy.sellerId());

        assertThat(policyProjectionRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber))
            .as("the policy itself is attributed to them, not merely the accrual")
            .get()
            .satisfies(projection ->
                assertThat(projection.getAgentId()).isEqualTo(hierarchy.sellerId()));
    }

    /**
     * The other half of every accrual test in this class: nothing is earned before the customer
     * pays.
     *
     * <p>Asserts the absence at both levels -- no projection row, so distribution has not even
     * learned of the policy, and no accrual, so no agent is owed anything. Before commission
     * moved to {@code PolicyActivated} this test would have found a full first-year accrual
     * against a contract the platform was not on risk for, which would then have had to be
     * clawed back off the agent when the offer expired unpaid.
     */
    @Test
    void noCommissionAccruesOnAnOfferNobodyHasPaidFor() {
        UUID tenantId = UUID.randomUUID();
        Hierarchy hierarchy = buildHierarchy(tenantId, "OFFER");

        String policyNumber = issueOffer(tenantId, hierarchy, hierarchy.sellerId(),
            new BigDecimal("100000.00"), "OFFER-01");

        TenantContext.set(tenantId);
        assertThat(policyProjectionRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber))
            .as("distribution should not have heard of a policy that is only an offer")
            .isEmpty();
        assertThat(commissionAccrualRepository
                .findByTenantIdAndPolicyNumberAndTierTypeAndReversesAccrualIdIsNull(
                    tenantId, policyNumber, TierType.FIRST_YEAR))
            .as("an agent has earned nothing until the customer has paid something")
            .isEmpty();
    }

    @Test
    void issuingAPolicyWithAnAgentOfRecordAccruesFirstYearAndBothOverrideLevels() {
        UUID tenantId = UUID.randomUUID();
        Hierarchy hierarchy = buildHierarchy(tenantId, "ACCRUE");
        BigDecimal premium = new BigDecimal("100000.00");

        String policyNumber = issuePolicy(tenantId, hierarchy, hierarchy.sellerId(), premium, "ACCRUE-01");

        // 1. The projection row -- distribution's own state, read back from the database.
        TenantContext.set(tenantId);
        PolicyProjection projection = policyProjectionRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber)
            .orElseThrow(() -> new AssertionError("Expected a policy_projection row for " + policyNumber));
        assertThat(projection.getAgentId()).isEqualTo(hierarchy.sellerId());
        assertThat(projection.getProductId()).isEqualTo(hierarchy.productId());
        assertThat(projection.getPremiumAmount()).isEqualByComparingTo(premium);
        assertThat(projection.getPremiumCurrency()).isEqualTo(CURRENCY);
        assertThat(projection.getIssueDate()).isEqualTo(LocalDate.now());

        String period = YearMonth.now().toString();

        // 2. The seller's FIRST_YEAR accrual: 10% of 100000.00 = 10000.00.
        assertSingleAccrualAndStatementTotal(tenantId, policyNumber, hierarchy.sellerId(), TierType.FIRST_YEAR,
            new BigDecimal("10000.00"), period);

        // 3. The parent's OVERRIDE accrual: 5% of 100000.00 = 5000.00.
        assertSingleAccrualAndStatementTotal(tenantId, policyNumber, hierarchy.parentId(), TierType.OVERRIDE,
            new BigDecimal("5000.00"), period);

        // 4. The grandparent's SUPERVISOR_OVERRIDE accrual: 2% of 100000.00 = 2000.00.
        assertSingleAccrualAndStatementTotal(tenantId, policyNumber, hierarchy.grandparentId(), TierType.SUPERVISOR_OVERRIDE,
            new BigDecimal("2000.00"), period);
    }

    private void assertSingleAccrualAndStatementTotal(UUID tenantId, String policyNumber, UUID agentId, TierType tierType,
                                                        BigDecimal expectedAmount, String period) {
        TenantContext.set(tenantId);
        List<CommissionAccrual> accruals = commissionAccrualRepository
            .findByTenantIdAndPolicyNumberAndTierTypeAndReversesAccrualIdIsNull(tenantId, policyNumber, tierType);
        assertThat(accruals).as("exactly one %s accrual for agent %s", tierType, agentId).hasSize(1);
        CommissionAccrual accrual = accruals.get(0);
        assertThat(accrual.getAgentId()).isEqualTo(agentId);
        assertThat(accrual.getAmount()).isEqualByComparingTo(expectedAmount);
        assertThat(accrual.getCurrency()).isEqualTo(CURRENCY);
        assertThat(accrual.getSourceRef()).isEqualTo(policyNumber);
        assertThat(accrual.getReversesAccrualId()).isNull();

        // The statement's total_amount, read back from the database -- not from any event.
        CommissionStatement statement = commissionStatementRepository
            .findByStatementIdAndTenantId(accrual.getStatementId(), tenantId)
            .orElseThrow(() -> new AssertionError("Expected a statement for accrual " + accrual.getAccrualId()));
        assertThat(statement.getAgentId()).isEqualTo(agentId);
        assertThat(statement.getPeriod()).isEqualTo(period);
        assertThat(statement.getTotalAmount()).isEqualByComparingTo(expectedAmount);
        assertThat(statement.getTotalCurrency()).isEqualTo(CURRENCY);
    }

    @Test
    void aRedeliveredPolicyIssuedCreatesNoSecondAccrualAndDoesNotChangeTheTotal() {
        UUID tenantId = UUID.randomUUID();
        Hierarchy hierarchy = buildHierarchy(tenantId, "REDELIVER");
        BigDecimal premium = new BigDecimal("50000.00");

        TenantContext.set(tenantId);
        PartyView policyholder = partyApi.registerIndividual("Distribution E2E Redeliver Policyholder",
            LocalDate.of(1980, 6, 1), "+255729999001", null, "test-agent");
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(policyholder.partyId(), hierarchy.productId(),
            hierarchy.productVersionId(), new BigDecimal("2000000"), CURRENCY, premium, CURRENCY, "MONTHLY",
            hierarchy.sellerId(), List.of(), "Distribution E2E test");
        String policyNumber = policyApi.issuePolicy(null, request, "test-staff").policyNumber();
        // Issued inline rather than through issuePolicy(...) because this test needs the request
        // in scope to rebuild the payload below -- so the first premium has to be collected here
        // too, or there is no accrual to redeliver against.
        TenantContext.set(tenantId);
        policyApi.activateOnFirstPremium(policyNumber);

        TenantContext.set(tenantId);
        List<CommissionAccrual> before = commissionAccrualRepository
            .findByTenantIdAndPolicyNumberAndTierTypeAndReversesAccrualIdIsNull(tenantId, policyNumber, TierType.FIRST_YEAR);
        assertThat(before).hasSize(1);
        CommissionStatement statementBefore = commissionStatementRepository
            .findByStatementIdAndTenantId(before.get(0).getStatementId(), tenantId).orElseThrow();
        BigDecimal totalBefore = statementBefore.getTotalAmount();

        // A real at-least-once redelivery of the SAME policy.PolicyActivated envelope, built
        // field-for-field from PolicyApiImpl.publishPolicyActivated's payload shape (not an
        // invented shape) -- same policyNumber, same agentOfRecordId, same premium.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("policyNumber", policyNumber);
        payload.put("policyholderPartyId", policyholder.partyId());
        payload.put("productId", hierarchy.productId());
        payload.put("productVersionId", null);
        payload.put("sumAssured", Map.of("amount", "2000000", "currencyCode", CURRENCY));
        payload.put("issueDate", LocalDate.now().toString());
        payload.put("premium", Map.of("amount", premium.toPlainString(), "currencyCode", CURRENCY));
        payload.put("premiumFrequency", "MONTHLY");
        payload.put("agentOfRecordId", hierarchy.sellerId());
        var envelope = DomainEventEnvelope.of("policy.PolicyActivated", tenantId, payload);
        TenantContext.set(tenantId);
        transactionTemplate().executeWithoutResult(status -> eventPublisher.publishEvent(envelope));

        // Still exactly one FIRST_YEAR accrual, and the statement's total is unchanged.
        TenantContext.set(tenantId);
        List<CommissionAccrual> after = commissionAccrualRepository
            .findByTenantIdAndPolicyNumberAndTierTypeAndReversesAccrualIdIsNull(tenantId, policyNumber, TierType.FIRST_YEAR);
        assertThat(after).hasSize(1);
        assertThat(after.get(0).getAccrualId()).isEqualTo(before.get(0).getAccrualId());
        CommissionStatement statementAfter = commissionStatementRepository
            .findByStatementIdAndTenantId(before.get(0).getStatementId(), tenantId).orElseThrow();
        assertThat(statementAfter.getTotalAmount()).isEqualByComparingTo(totalBefore);
    }

    @Test
    void aPolicyIssuedWithANullAgentOfRecordCreatesAProjectionRowAndZeroAccruals() {
        UUID tenantId = UUID.randomUUID();
        Hierarchy hierarchy = buildHierarchy(tenantId, "DIRECT");

        String policyNumber = issuePolicy(tenantId, hierarchy, null, new BigDecimal("75000.00"), "DIRECT-01");

        TenantContext.set(tenantId);
        PolicyProjection projection = policyProjectionRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber)
            .orElseThrow(() -> new AssertionError("Expected a policy_projection row for a direct-sold policy"));
        assertThat(projection.getAgentId()).isNull();
        assertThat(projection.getPremiumAmount()).isEqualByComparingTo("75000.00");

        for (TierType tierType : new TierType[] {TierType.FIRST_YEAR, TierType.OVERRIDE, TierType.SUPERVISOR_OVERRIDE}) {
            assertThat(commissionAccrualRepository
                .findByTenantIdAndPolicyNumberAndTierTypeAndReversesAccrualIdIsNull(tenantId, policyNumber, tierType))
                .as("a direct-sold policy must accrue zero %s commission", tierType)
                .isEmpty();
        }
    }

    // =========================================================================================
    // DistributionApi's two read methods. Added after Task 8: an audit of the module's public
    // surface found listStatements and listAccruals had ZERO test call sites anywhere in the
    // suite, the same gap that let requestStatementPayout ship over a column too narrow to store
    // its own status. Exercised here rather than in DistributionApiIntegrationTest because this
    // class produces real statements and accruals through the real event chain, so the reads are
    // asserted against genuinely-produced rows rather than hand-built ones.
    // =========================================================================================

    @Test
    void listStatementsReturnsTheAgentsStatementAndFiltersByPeriod() {
        UUID tenantId = UUID.randomUUID();
        Hierarchy hierarchy = buildHierarchy(tenantId, "LIST-STMT");
        String policyNumber = issuePolicy(tenantId, hierarchy, hierarchy.sellerId(), new BigDecimal("100000.00"), "LIST-STMT-01");
        String period = YearMonth.now().toString();

        TenantContext.set(tenantId);
        List<CommissionStatementView> all = distributionApi.listStatements(hierarchy.sellerId(), null);
        assertThat(all).hasSize(1);
        assertThat(all.get(0).agentId()).isEqualTo(hierarchy.sellerId());
        assertThat(all.get(0).period()).isEqualTo(period);
        assertThat(all.get(0).totalAmount()).isEqualByComparingTo(new BigDecimal("10000.00"));
        assertThat(all.get(0).totalCurrency()).isEqualTo(CURRENCY);
        assertThat(all.get(0).status()).isEqualTo(StatementStatus.OPEN);
        assertThat(all.get(0).closedAt()).isNull();
        assertThat(all.get(0).paidAt()).isNull();

        TenantContext.set(tenantId);
        assertThat(distributionApi.listStatements(hierarchy.sellerId(), period)).hasSize(1);
        // The falsifiable half: a filter that ignored its argument would return the row here too.
        TenantContext.set(tenantId);
        assertThat(distributionApi.listStatements(hierarchy.sellerId(), "1999-01")).isEmpty();

        assertThat(policyNumber).isNotBlank();
    }

    @Test
    void listStatementsThrowsForAnAgentThatDoesNotExistInThisTenant() {
        UUID tenantId = UUID.randomUUID();
        buildHierarchy(tenantId, "LIST-STMT-404");

        TenantContext.set(tenantId);
        UUID unknownAgentId = UUID.randomUUID();
        // Not an empty list: an unknown or cross-tenant agentId must 404 rather than look like an
        // agent who simply earned nothing.
        assertThrows(AgentNotFoundException.class, () -> distributionApi.listStatements(unknownAgentId, null));
    }

    @Test
    void listAccrualsReturnsTheStatementsLineItems() {
        UUID tenantId = UUID.randomUUID();
        Hierarchy hierarchy = buildHierarchy(tenantId, "LIST-ACCR");
        String policyNumber = issuePolicy(tenantId, hierarchy, hierarchy.sellerId(), new BigDecimal("100000.00"), "LIST-ACCR-01");

        TenantContext.set(tenantId);
        UUID statementId = distributionApi.listStatements(hierarchy.sellerId(), null).get(0).statementId();

        TenantContext.set(tenantId);
        List<CommissionAccrualView> accruals = distributionApi.listAccruals(statementId);
        assertThat(accruals).hasSize(1);
        CommissionAccrualView accrual = accruals.get(0);
        assertThat(accrual.statementId()).isEqualTo(statementId);
        assertThat(accrual.agentId()).isEqualTo(hierarchy.sellerId());
        assertThat(accrual.policyNumber()).isEqualTo(policyNumber);
        assertThat(accrual.tierType()).isEqualTo(TierType.FIRST_YEAR);
        assertThat(accrual.amount()).isEqualByComparingTo(new BigDecimal("10000.00"));
        assertThat(accrual.currency()).isEqualTo(CURRENCY);
        assertThat(accrual.sourceRef()).isEqualTo(policyNumber);
        assertThat(accrual.reversesAccrualId()).isNull();
    }

    @Test
    void listAccrualsThrowsForAStatementThatDoesNotExistInThisTenant() {
        UUID tenantId = UUID.randomUUID();
        buildHierarchy(tenantId, "LIST-ACCR-404");

        TenantContext.set(tenantId);
        UUID unknownStatementId = UUID.randomUUID();
        assertThrows(CommissionStatementNotFoundException.class,
            () -> distributionApi.listAccruals(unknownStatementId));
    }

    // =========================================================================================
    // Task 7 -- RENEWAL accrual driven by billing.PremiumCollected
    // =========================================================================================

    /**
     * A real {@code billing.PremiumCollected} envelope, built field-for-field from {@code
     * BillingApiImpl.applyConfirmedPayment}'s published payload shape -- not an invented one.
     * Published directly rather than driven through {@code BillingApi} because this class
     * deliberately does not apply {@code billing}'s migrations; {@code BillingApiIntegrationTest}
     * owns the proof that the producer really emits this shape, on the right edge, and this class
     * owns the proof of what {@code distribution} does with it.
     */
    private void collectPremium(UUID tenantId, String policyNumber, UUID invoiceId, BigDecimal amount) {
        Map<String, Object> payload = Map.of(
            "invoiceId", invoiceId,
            "policyNumber", policyNumber,
            "amount", Map.of("amount", amount.toPlainString(), "currencyCode", CURRENCY),
            "collectedAt", Instant.now().toString());
        var envelope = DomainEventEnvelope.of("billing.PremiumCollected", tenantId, payload);
        TenantContext.set(tenantId);
        transactionTemplate().executeWithoutResult(status -> eventPublisher.publishEvent(envelope));
    }

    private List<CommissionAccrual> accruals(UUID tenantId, String policyNumber, TierType tierType) {
        TenantContext.set(tenantId);
        return commissionAccrualRepository
            .findByTenantIdAndPolicyNumberAndTierTypeAndReversesAccrualIdIsNull(tenantId, policyNumber, tierType);
    }

    @Test
    void theFirstCollectedPremiumAccruesNoRenewalAndRecordsWhichInvoiceItWas() {
        UUID tenantId = UUID.randomUUID();
        Hierarchy hierarchy = buildHierarchy(tenantId, "RENEW-FIRST");
        String policyNumber = issuePolicy(tenantId, hierarchy, hierarchy.sellerId(), new BigDecimal("100000.00"), "RENEW-FIRST-01");
        UUID firstInvoiceId = UUID.randomUUID();

        collectPremium(tenantId, policyNumber, firstInvoiceId, new BigDecimal("100000.00"));

        // Issuance already paid FIRST_YEAR on this premium -- a RENEWAL here would pay twice.
        assertThat(accruals(tenantId, policyNumber, TierType.RENEWAL))
            .as("the first collected premium must accrue no RENEWAL")
            .isEmpty();
        // The falsifiable half: the projection records WHICH invoice, not merely that one landed.
        TenantContext.set(tenantId);
        PolicyProjection projection = policyProjectionRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber)
            .orElseThrow(() -> new AssertionError("Expected a policy_projection row for " + policyNumber));
        assertThat(projection.getFirstInvoiceId()).isEqualTo(firstInvoiceId);
    }

    @Test
    void aSecondCollectedPremiumAccruesExactlyOneRenewalForTheSellerAndNoOverrides() {
        UUID tenantId = UUID.randomUUID();
        Hierarchy hierarchy = buildHierarchy(tenantId, "RENEW-SECOND");
        BigDecimal premium = new BigDecimal("100000.00");
        String policyNumber = issuePolicy(tenantId, hierarchy, hierarchy.sellerId(), premium, "RENEW-SECOND-01");

        collectPremium(tenantId, policyNumber, UUID.randomUUID(), premium);
        UUID secondInvoiceId = UUID.randomUUID();
        collectPremium(tenantId, policyNumber, secondInvoiceId, premium);

        // Exactly one RENEWAL, for the SELLER, at 3% of 100000.00, keyed on the invoice.
        List<CommissionAccrual> renewals = accruals(tenantId, policyNumber, TierType.RENEWAL);
        assertThat(renewals).hasSize(1);
        assertThat(renewals.get(0).getAgentId()).isEqualTo(hierarchy.sellerId());
        assertThat(renewals.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("3000.00"));
        assertThat(renewals.get(0).getCurrency()).isEqualTo(CURRENCY);
        assertThat(renewals.get(0).getSourceRef()).isEqualTo(secondInvoiceId.toString());

        // A renewal does NOT re-pay the hierarchy (CommissionCalculator's documented rule): the
        // override tiers must still hold exactly the ONE accrual each that issuance created, so
        // this fails if a renewal ever starts walking ancestors.
        assertThat(accruals(tenantId, policyNumber, TierType.OVERRIDE))
            .as("a renewal must not re-pay the parent").hasSize(1);
        assertThat(accruals(tenantId, policyNumber, TierType.SUPERVISOR_OVERRIDE))
            .as("a renewal must not re-pay the grandparent").hasSize(1);
    }

    /**
     * The guard that a boolean {@code first_invoice_collected} flag could not provide. Once the
     * first collection had flipped a boolean, a redelivery of that SAME event would read as a
     * second invoice and accrue the RENEWAL the first-invoice guard exists to prevent -- and
     * {@code sourceRef} dedup could not catch it, because the first collection deliberately writes
     * no accrual row to collide with. Storing {@code first_invoice_id} is what closes it.
     */
    @Test
    void aRedeliveredFirstCollectionStillAccruesNoRenewal() {
        UUID tenantId = UUID.randomUUID();
        Hierarchy hierarchy = buildHierarchy(tenantId, "RENEW-REDELIVER-FIRST");
        BigDecimal premium = new BigDecimal("100000.00");
        String policyNumber = issuePolicy(tenantId, hierarchy, hierarchy.sellerId(), premium, "RENEW-RD-FIRST-01");
        UUID firstInvoiceId = UUID.randomUUID();

        collectPremium(tenantId, policyNumber, firstInvoiceId, premium);
        // Negative control: the guard is genuinely armed before the redelivery, so this test is not
        // vacuously passing against a policy whose first collection never registered.
        TenantContext.set(tenantId);
        assertThat(policyProjectionRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber)
            .orElseThrow().getFirstInvoiceId()).isEqualTo(firstInvoiceId);

        collectPremium(tenantId, policyNumber, firstInvoiceId, premium);

        assertThat(accruals(tenantId, policyNumber, TierType.RENEWAL))
            .as("a redelivered FIRST collection must never accrue RENEWAL")
            .isEmpty();
    }

    @Test
    void aRedeliveredLaterCollectionAccruesNoSecondRenewalAndLeavesTheTotalUnchanged() {
        UUID tenantId = UUID.randomUUID();
        Hierarchy hierarchy = buildHierarchy(tenantId, "RENEW-REDELIVER-LATER");
        BigDecimal premium = new BigDecimal("100000.00");
        String policyNumber = issuePolicy(tenantId, hierarchy, hierarchy.sellerId(), premium, "RENEW-RD-LATER-01");
        UUID secondInvoiceId = UUID.randomUUID();

        collectPremium(tenantId, policyNumber, UUID.randomUUID(), premium);
        collectPremium(tenantId, policyNumber, secondInvoiceId, premium);

        List<CommissionAccrual> before = accruals(tenantId, policyNumber, TierType.RENEWAL);
        assertThat(before).hasSize(1);
        TenantContext.set(tenantId);
        BigDecimal totalBefore = commissionStatementRepository
            .findByStatementIdAndTenantId(before.get(0).getStatementId(), tenantId).orElseThrow().getTotalAmount();

        collectPremium(tenantId, policyNumber, secondInvoiceId, premium);

        List<CommissionAccrual> after = accruals(tenantId, policyNumber, TierType.RENEWAL);
        assertThat(after).hasSize(1);
        assertThat(after.get(0).getAccrualId()).isEqualTo(before.get(0).getAccrualId());
        TenantContext.set(tenantId);
        assertThat(commissionStatementRepository
            .findByStatementIdAndTenantId(before.get(0).getStatementId(), tenantId).orElseThrow().getTotalAmount())
            .isEqualByComparingTo(totalBefore);
    }

    @Test
    void aPremiumCollectedForAPolicyWithNoProjectionRowAccruesNothingAndDoesNotThrow() {
        UUID tenantId = UUID.randomUUID();
        buildHierarchy(tenantId, "RENEW-PRE-M7");

        // A pre-M7 policy: never issued through this module's listener, so it has no projection.
        collectPremium(tenantId, "POL-PRE-M7-0001", UUID.randomUUID(), new BigDecimal("100000.00"));

        assertThat(accruals(tenantId, "POL-PRE-M7-0001", TierType.RENEWAL)).isEmpty();
        TenantContext.set(tenantId);
        assertThat(policyProjectionRepository.findByTenantIdAndPolicyNumber(tenantId, "POL-PRE-M7-0001")).isEmpty();
    }
}
