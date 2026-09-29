package tz.co.nlolo.lifeplatform.distribution;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.SpecTypeConformance;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.distribution.api.DistributionApi;
import tz.co.nlolo.lifeplatform.distribution.api.StatementStatus;
import tz.co.nlolo.lifeplatform.distribution.api.TierType;
import tz.co.nlolo.lifeplatform.distribution.domain.CommissionStatement;
import tz.co.nlolo.lifeplatform.distribution.infrastructure.CommissionStatementRepository;
import tz.co.nlolo.lifeplatform.party.api.KycStatus;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.product.api.BenefitType;
import tz.co.nlolo.lifeplatform.product.api.FactorType;
import tz.co.nlolo.lifeplatform.product.api.IfrsMeasurementModel;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.ProductCategory;
import tz.co.nlolo.lifeplatform.product.api.ProductSummaryView;
import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

// Deliberately NOT a wildcard import: WireMock.post(String) and MockMvcRequestBuilders.post(String)
// collide, and this class needs both. WireMock's stub builder stays qualified as WireMock.post(...).
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;
import tz.co.nlolo.lifeplatform.product.api.BenefitCalculationMethod;

/**
 * Task 10: HTTP-level contract coverage for {@code AgentController}/{@code CommissionPlanController}
 * against {@code api/openapi/openapi-distribution.yaml}. Follows {@code ClaimsContractTest}'s
 * structure: {@code @Testcontainers} + {@code @AutoConfigureMockMvc} + {@code @SpringBootTest} +
 * {@code jwt()} post-processors + {@code openApi().isValid(SPEC_PATH)} <b>paired with</b>
 * {@link SpecTypeConformance#matchesDeclaredTypes} on every response carrying a decimal --
 * {@code isValid} provably does NOT enforce primitive JSON types, which is how a
 * {@code type: string} field emitted as a bare number slipped through in M6.
 *
 * <p>Fixtures are seeded through the module APIs directly; only the operation actually under test
 * goes through HTTP. <b>Every 403 test seeds a real, valid target first</b>, so a broken
 * {@code @PreAuthorize} or ownership check surfaces as 200/201/202 rather than an incidental 404
 * that would pass for the wrong reason.
 *
 * <p>WireMock and payment's migrations are present only because the payout endpoint's 202 path
 * genuinely publishes {@code distribution.CommissionPayoutRequested}, which {@code payment}'s
 * AFTER_COMMIT listener consumes synchronously on the same thread and drives to the rail. Without
 * them the endpoint would still answer 202 (that listener swallows and logs its own failures), so
 * the test would pass while the chain behind it was broken -- exactly the kind of hollow green
 * this suite has been bitten by before.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class DistributionContractTest {

    private static final String SPEC_PATH = "api/openapi/openapi-distribution.yaml";
    private static final String CURRENCY = "TZS";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static WireMockServer wireMock;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("mobile-money.base-url", () -> wireMock.baseUrl());
    }

    @BeforeAll
    static void startGatewayAndApplyMigrations() throws Exception {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/refdata/V3__seed_billing_parameters.sql",
            "db-migrations/refdata/V4__seed_distribution_parameters.sql",
            "db-migrations/party/V1__create_party_schema.sql",
            "db-migrations/party/V2__individual_person_record.sql",
            "db-migrations/party/V4__registered_by_agent.sql",
            "db-migrations/party/V5__registered_by_name.sql",
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
            "db-migrations/policy/V25__credit_life_premium_basis.sql",
            "db-migrations/distribution/V1__create_distribution_schema.sql",
            "db-migrations/distribution/V2__grants_rls_money_checks_projection_and_statement_lifecycle.sql",
            "db-migrations/payment/V1__create_payment_schema.sql",
            "db-migrations/payment/V2__grants_rls_money_checks_version_and_tenant_scoped_registries.sql",
            "db-migrations/payment/V3__inbound_callback_tenant_resolver.sql",
            "db-migrations/payment/V4__in_doubt_status_and_id_based_callback_resolvers.sql",
            "db-migrations/payment/V6__disbursement_method.sql");
    }

    @AfterAll
    static void stopGateway() { wireMock.stop(); }

    @Autowired private MockMvc mockMvc;
    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private PolicyApi policyApi;
    @Autowired private DistributionApi distributionApi;
    @Autowired private CommissionStatementRepository commissionStatementRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    @AfterEach
    void resetAfterEach() {
        TenantContext.clear();
        wireMock.resetAll();
    }

    private TransactionTemplate transactionTemplate() {
        if (transactionTemplate == null) {
            transactionTemplate = new TransactionTemplate(transactionManager);
        }
        return transactionTemplate;
    }

    // --- token shapes -------------------------------------------------------------------------

    /** FINANCE_OFFICER is the role AgentController's javadoc records as the decision for
     * "onboard, administer" -- there is no AGENCY_MANAGER role in the staff realm. */
    private static RequestPostProcessor financeStaffOf(UUID tenantId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"),
                                 new SimpleGrantedAuthority("ROLE_FINANCE_OFFICER"))
            .jwt(builder -> builder.subject("finance-officer").claim("tenant_id", tenantId.toString()));
    }

    /** Staff, but the WRONG fine-grained role -- proves the gate is on FINANCE_OFFICER/ADMIN and
     * not merely on being staff. */
    private static RequestPostProcessor underwriterStaffOf(UUID tenantId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"),
                                 new SimpleGrantedAuthority("ROLE_UNDERWRITER"))
            .jwt(builder -> builder.subject("underwriter").claim("tenant_id", tenantId.toString()));
    }

    private static RequestPostProcessor agentOf(UUID tenantId, UUID partyId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
            .jwt(builder -> builder.subject("agent").claim("tenant_id", tenantId.toString())
                .claim("party_id", partyId.toString()));
    }

    // --- fixtures -----------------------------------------------------------------------------

    private UUID createActiveProduct(UUID tenantId, String code) {
        TenantContext.set(tenantId);
        ProductSummaryView product = productApi.createProduct(code, "Distribution Contract Product " + code,
            ProductCategory.TERM_LIFE, CURRENCY, "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary");
        TenantContext.clear();
        return product.productId();
    }

    private PartyView verifiedParty(UUID tenantId, String tag) {
        TenantContext.set(tenantId);
        PartyView party = partyApi.registerIndividual("Distribution Contract " + tag, LocalDate.of(1985, 1, 1),
            "+25576" + String.format("%07d", Math.abs((tag + tenantId).hashCode() % 10000000)), null, "test-agent");
        partyApi.submitKycEvidence(party.partyId(), KycStatus.VERIFIED, "doc-" + tag, "kyc-officer");
        TenantContext.clear();
        return party;
    }

    private record Agent(UUID agentId, UUID partyId) {}

    /** Licence numbers must stay within {@code agent_profile.license_number}'s VARCHAR(50): a
     * full UUID suffix plus a descriptive tag overruns it, and the resulting integrity violation
     * used to be reported as a duplicate licence (fixed in DistributionApiImpl, pinned by
     * {@link #onboardAgentReturns422WithAnAccurateMessageForAnOverLongLicenceNumber}). Eight hex
     * characters are ample for uniqueness within one test container. */
    private Agent onboardAgent(UUID tenantId, String tag, UUID parentId) {
        PartyView party = verifiedParty(tenantId, tag);
        TenantContext.set(tenantId);
        String licenseNumber = "LIC-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8);
        UUID agentId = distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            party.partyId(), licenseNumber, LocalDate.now().plusYears(1), parentId),
            "staff-fixture").agentId();
        TenantContext.clear();
        return new Agent(agentId, party.partyId());
    }

    private void createPlan(UUID tenantId, UUID productId) {
        TenantContext.set(tenantId);
        distributionApi.createCommissionPlan(productId, List.of(
            new DistributionApi.CommissionRuleInput(TierType.FIRST_YEAR, new BigDecimal("0.10"), null, null)),
            "actuary");
        TenantContext.clear();
    }

    /** Issues a real policy so a real FIRST_YEAR accrual and its OPEN statement exist. */
    private UUID statementFor(UUID tenantId, UUID productId, UUID agentId, String tag) {
        TenantContext.set(tenantId);
        UUID productVersionId = productApi.getActiveSnapshot(productId, LocalDate.now()).productVersionId();
        PartyView policyholder = partyApi.registerIndividual("Distribution Contract Holder " + tag,
            LocalDate.of(1980, 6, 1), "+25577" + String.format("%07d", Math.abs((tag + tenantId).hashCode() % 10000000)),
            null, "test-agent");
        String policyNumber = policyApi.issuePolicy(null, new PolicyApi.IssueRequest(policyholder.partyId(),
            productId, productVersionId, new BigDecimal("2000000"), CURRENCY, new BigDecimal("100000.00"),
            CURRENCY, "MONTHLY", agentId, List.of(), "Distribution contract test"), "test-staff").policyNumber();
        // No commission statement exists until cover starts, so the lookup below finds nothing
        // without this.
        policyApi.activateOnFirstPremium(policyNumber);
        UUID statementId = commissionStatementRepository
            .findByTenantIdAndAgentIdOrderByPeriodDesc(tenantId, agentId).get(0).getStatementId();
        TenantContext.clear();
        return statementId;
    }

    private void closeStatement(UUID tenantId, UUID statementId) {
        transactionTemplate().executeWithoutResult(status -> {
            TenantContext.set(tenantId);
            CommissionStatement statement = commissionStatementRepository
                .findByStatementIdAndTenantId(statementId, tenantId).orElseThrow();
            statement.close(Instant.now());
            commissionStatementRepository.save(statement);
        });
        TenantContext.clear();
    }

    private static String onboardBody(UUID partyId, String licenseNumber) {
        return """
            {"partyId":"%s","licenseNumber":"%s","licenseExpiryDate":"%s"}
            """.formatted(partyId, licenseNumber, LocalDate.now().plusYears(1));
    }

    // ============================================================================================
    // POST /agents
    // ============================================================================================

    @Test
    void onboardAgentReturns201ForAVerifiedParty() throws Exception {
        UUID tenantId = UUID.randomUUID();
        PartyView party = verifiedParty(tenantId, "ONB-201");

        mockMvc.perform(post("/agents").with(financeStaffOf(tenantId))
                .header("Idempotency-Key", "ct-onb-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(onboardBody(party.partyId(), "LIC-CT-ONB-201")))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.licenseStatus").value("ACTIVE"))
            .andExpect(jsonPath("$.partyId").value(party.partyId().toString()));
    }

    @Test
    void onboardAgentReturns422WhenThePartyIsNotKycVerified() throws Exception {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        PartyView unverified = partyApi.registerIndividual("Unverified Contract Agent", LocalDate.of(1985, 1, 1),
            "+255760000001", null, "test-agent");
        TenantContext.clear();

        mockMvc.perform(post("/agents").with(financeStaffOf(tenantId))
                .header("Idempotency-Key", "ct-onb-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(onboardBody(unverified.partyId(), "LIC-CT-ONB-422")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("DISTRIBUTION_VALIDATION_FAILED"))
            .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void onboardAgentReturns422ForADuplicateLicenceNumber() throws Exception {
        UUID tenantId = UUID.randomUUID();
        PartyView first = verifiedParty(tenantId, "DUP-A");
        PartyView second = verifiedParty(tenantId, "DUP-B");
        mockMvc.perform(post("/agents").with(financeStaffOf(tenantId))
                .header("Idempotency-Key", "ct-onb-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(onboardBody(first.partyId(), "LIC-CT-DUPLICATE")))
            .andExpect(status().isCreated());

        // A 500 here would mean ux_agent_license's violation escaped as a raw
        // DataIntegrityViolationException instead of the mapped validation failure.
        mockMvc.perform(post("/agents").with(financeStaffOf(tenantId))
                .header("Idempotency-Key", "ct-onb-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(onboardBody(second.partyId(), "LIC-CT-DUPLICATE")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errorCode").value("DISTRIBUTION_VALIDATION_FAILED"));
    }

    /**
     * Regression guard for a real mislabeling bug this test class surfaced. {@code license_number}
     * is VARCHAR(50), and {@code onboardAgent}'s catch used to convert EVERY
     * {@code DataIntegrityViolationException} into "License number '...' is already in use in this
     * tenant" -- so an over-long licence reported a duplicate collision against a value nothing
     * had ever used. The catch is now narrowed to {@code ux_agent_license}, and length is checked
     * up front, so the message matches reality.
     */
    @Test
    void onboardAgentReturns422WithAnAccurateMessageForAnOverLongLicenceNumber() throws Exception {
        UUID tenantId = UUID.randomUUID();
        PartyView party = verifiedParty(tenantId, "ONB-LONG");
        String overLong = "LIC-" + "X".repeat(60);

        mockMvc.perform(post("/agents").with(financeStaffOf(tenantId))
                .header("Idempotency-Key", "ct-onb-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(onboardBody(party.partyId(), overLong)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errorCode").value("DISTRIBUTION_VALIDATION_FAILED"))
            // The load-bearing assertion: it must NOT claim the licence is already in use.
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("the maximum is 50")));
    }

    @Test
    void onboardAgentReturns403ForAnAgentToken() throws Exception {
        UUID tenantId = UUID.randomUUID();
        // Seeded REAL and valid, so a broken @PreAuthorize returns 201, not an incidental 422.
        PartyView party = verifiedParty(tenantId, "ONB-403");
        Agent caller = onboardAgent(tenantId, "ONB-403-CALLER", null);

        mockMvc.perform(post("/agents").with(agentOf(tenantId, caller.partyId()))
                .header("Idempotency-Key", "ct-onb-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(onboardBody(party.partyId(), "LIC-CT-ONB-403")))
            .andExpect(status().isForbidden());
    }

    @Test
    void onboardAgentReturns403ForStaffWithoutTheFinanceRole() throws Exception {
        UUID tenantId = UUID.randomUUID();
        PartyView party = verifiedParty(tenantId, "ONB-403-UW");

        mockMvc.perform(post("/agents").with(underwriterStaffOf(tenantId))
                .header("Idempotency-Key", "ct-onb-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(onboardBody(party.partyId(), "LIC-CT-ONB-403-UW")))
            .andExpect(status().isForbidden());
    }

    @Test
    void onboardAgentReturns400WhenTheIdempotencyKeyIsMissing() throws Exception {
        UUID tenantId = UUID.randomUUID();
        PartyView party = verifiedParty(tenantId, "ONB-400");

        // No isValid matcher: the SPEC declares Idempotency-Key required, so this request is
        // deliberately spec-invalid and isValid validates requests too. Status + errorCode only.
        mockMvc.perform(post("/agents").with(financeStaffOf(tenantId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(onboardBody(party.partyId(), "LIC-CT-ONB-400")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    // ============================================================================================
    // GET /agents/{agentId} -- the object-level checks
    // ============================================================================================

    @Test
    void getAgentReturns200ForStaff() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Agent agent = onboardAgent(tenantId, "GET-STAFF", null);

        mockMvc.perform(get("/agents/{agentId}", agent.agentId()).with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.agentId").value(agent.agentId().toString()));
    }

    @Test
    void getAgentReturns200ForTheAgentItself() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Agent agent = onboardAgent(tenantId, "GET-SELF", null);

        mockMvc.perform(get("/agents/{agentId}", agent.agentId()).with(agentOf(tenantId, agent.partyId())))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.agentId").value(agent.agentId().toString()));
    }

    @Test
    void getAgentReturns403ForAnUnrelatedAgentInTheSameTenant() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Agent target = onboardAgent(tenantId, "GET-403-TARGET", null);
        Agent stranger = onboardAgent(tenantId, "GET-403-STRANGER", null);

        // Both are real and in the SAME tenant, so a broken ownership check returns 200 here,
        // not a 404 that would pass for the wrong reason.
        mockMvc.perform(get("/agents/{agentId}", target.agentId()).with(agentOf(tenantId, stranger.partyId())))
            .andExpect(status().isForbidden());
    }

    @Test
    void getAgentReturns200ForASupervisorReadingItsOwnDescendant() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Agent supervisor = onboardAgent(tenantId, "SUP", null);
        Agent subordinate = onboardAgent(tenantId, "SUB", supervisor.agentId());

        mockMvc.perform(get("/agents/{agentId}", subordinate.agentId())
                .with(agentOf(tenantId, supervisor.partyId())))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.hierarchyParentId").value(supervisor.agentId().toString()));
    }

    @Test
    void getAgentReturns403ForASupervisorReadingOutsideItsOwnHierarchy() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Agent supervisor = onboardAgent(tenantId, "SUP-B", null);
        onboardAgent(tenantId, "SUB-B", supervisor.agentId());          // a real downline exists
        Agent otherBranch = onboardAgent(tenantId, "OTHER-BRANCH", null);

        // The caller IS a supervisor of someone -- just not of this agent. Without that, a check
        // that merely asked "does the caller have any downline?" would pass.
        mockMvc.perform(get("/agents/{agentId}", otherBranch.agentId())
                .with(agentOf(tenantId, supervisor.partyId())))
            .andExpect(status().isForbidden());
    }

    @Test
    void getAgentReturns404ForAnUnknownId() throws Exception {
        UUID tenantId = UUID.randomUUID();

        mockMvc.perform(get("/agents/{agentId}", UUID.randomUUID()).with(financeStaffOf(tenantId)))
            .andExpect(status().isNotFound())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("AGENT_NOT_FOUND"));
    }

    @Test
    void getAgentReturns404NotForbiddenForAnAgentInAnotherTenant() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        Agent inTenantA = onboardAgent(tenantA, "XT-A", null);

        // 404, never 403: a 403 would confirm to tenant B that this id exists somewhere.
        mockMvc.perform(get("/agents/{agentId}", inTenantA.agentId()).with(financeStaffOf(tenantB)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("AGENT_NOT_FOUND"));
    }

    // ============================================================================================
    // GET /agents/me -- the caller's own agentId, since nothing else on the platform exposes it
    // ============================================================================================

    @Test
    void getOwnAgentProfileReturns200ForTheCallersOwnAgent() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Agent agent = onboardAgent(tenantId, "ME", null);

        mockMvc.perform(get("/agents/me").with(agentOf(tenantId, agent.partyId())))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.agentId").value(agent.agentId().toString()));
    }

    @Test
    void getOwnAgentProfileReturns404WhenThePartyIsNotAnAgent() throws Exception {
        UUID tenantId = UUID.randomUUID();
        // A real party with no AgentProfile at all -- proves this 404s rather than 500ing or
        // matching some unrelated agent.
        PartyView notAnAgent = verifiedParty(tenantId, "NOT-AN-AGENT");

        mockMvc.perform(get("/agents/me").with(agentOf(tenantId, notAnAgent.partyId())))
            .andExpect(status().isNotFound())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("AGENT_NOT_FOUND"));
    }

    @Test
    void getOwnAgentProfileReturns403ForStaff() throws Exception {
        // Staff has no "own agent" concept -- unlike GET /agents/{agentId}, which staff reads
        // freely, this endpoint is agents-realm only.
        UUID tenantId = UUID.randomUUID();

        mockMvc.perform(get("/agents/me").with(financeStaffOf(tenantId)))
            .andExpect(status().isForbidden());
    }

    // ============================================================================================
    // GET /agents/{agentId}/commission-plan
    // ============================================================================================

    @Test
    void getApplicablePlanReturns200WithItsRules() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID productId = createActiveProduct(tenantId, "DIST-CT-PLAN");
        createPlan(tenantId, productId);
        Agent agent = onboardAgent(tenantId, "PLAN-200", null);

        mockMvc.perform(get("/agents/{agentId}/commission-plan", agent.agentId())
                .param("productId", productId.toString())
                .with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            // Paired with isValid because rate is a DECIMAL STRING in the spec: isValid does not
            // enforce primitive JSON types, so a bare number here would otherwise pass.
            .andExpect(SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "CommissionPlanView"))
            .andExpect(jsonPath("$.rules[0].tierType").value("FIRST_YEAR"))
            .andExpect(jsonPath("$.rules[0].rate").value("0.1000"));
    }

    @Test
    void getApplicablePlanReturns404WhenNoPlanApplies() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID productId = createActiveProduct(tenantId, "DIST-CT-NOPLAN");
        Agent agent = onboardAgent(tenantId, "PLAN-404", null);

        mockMvc.perform(get("/agents/{agentId}/commission-plan", agent.agentId())
                .param("productId", productId.toString())
                .with(financeStaffOf(tenantId)))
            .andExpect(status().isNotFound())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("COMMISSION_PLAN_NOT_FOUND"));
    }

    // ============================================================================================
    // GET /agents/{agentId}/commission-statements
    // ============================================================================================

    @Test
    void listStatementsReturns200AndThePeriodFilterGenuinelyNarrows() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID productId = createActiveProduct(tenantId, "DIST-CT-STMT");
        createPlan(tenantId, productId);
        Agent agent = onboardAgent(tenantId, "STMT-200", null);
        statementFor(tenantId, productId, agent.agentId(), "STMT-200");

        mockMvc.perform(get("/agents/{agentId}/commission-statements", agent.agentId())
                .with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "CommissionStatementView"))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].totalAmount.amount").value("10000.00"))
            .andExpect(jsonPath("$[0].status").value("OPEN"));

        mockMvc.perform(get("/agents/{agentId}/commission-statements", agent.agentId())
                .param("period", YearMonth.now().toString())
                .with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));

        // The falsifiable half -- a filter that ignored its argument would still return the row.
        mockMvc.perform(get("/agents/{agentId}/commission-statements", agent.agentId())
                .param("period", "1999-01")
                .with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    /**
     * Whole-branch review finding, confirmed before being fixed: {@code
     * DistributionApi.listAccruals(UUID statementId)} takes no {@code agentId} at all, and the
     * controller checks only that the CALLER may read the path's {@code agentId} -- it never
     * checks that the {@code statementId} in the same path actually belongs to that agent. So any
     * agent (or supervisor, or staff token) who can read their OWN record could pair their own
     * valid {@code agentId} with a DIFFERENT agent's real {@code statementId} and read that
     * agent's commission line items -- policy number, tier, amount -- same-tenant IDOR via a
     * path that merely nests one resource under another without verifying the nesting.
     */
    @Test
    void listAccrualsReturns404WhenTheStatementBelongsToADifferentAgent() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID productId = createActiveProduct(tenantId, "DIST-CT-IDOR");
        createPlan(tenantId, productId);
        Agent owner = onboardAgent(tenantId, "IDOR-OWNER", null);
        Agent caller = onboardAgent(tenantId, "IDOR-CALLER", null);
        UUID ownersStatementId = statementFor(tenantId, productId, owner.agentId(), "IDOR-OWNER");

        // caller reads its OWN agentId (a legitimate 200 for the agentId check alone) but asks
        // for OWNER's real statementId nested underneath it.
        mockMvc.perform(get("/agents/{agentId}/commission-statements/{statementId}/accruals",
                    caller.agentId(), ownersStatementId)
                .with(financeStaffOf(tenantId)))
            .andExpect(status().isNotFound());

        // Same probe from the agent realm too -- caller reading its own record, someone else's
        // statement id.
        mockMvc.perform(get("/agents/{agentId}/commission-statements/{statementId}/accruals",
                    caller.agentId(), ownersStatementId)
                .with(agentOf(tenantId, caller.partyId())))
            .andExpect(status().isNotFound());
    }

    @Test
    void listAccrualsReturns200WithTheLineItems() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID productId = createActiveProduct(tenantId, "DIST-CT-ACCR");
        createPlan(tenantId, productId);
        Agent agent = onboardAgent(tenantId, "ACCR-200", null);
        UUID statementId = statementFor(tenantId, productId, agent.agentId(), "ACCR-200");

        mockMvc.perform(get("/agents/{agentId}/commission-statements/{statementId}/accruals",
                    agent.agentId(), statementId)
                .with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "CommissionAccrualView"))
            .andExpect(jsonPath("$[0].tierType").value("FIRST_YEAR"))
            .andExpect(jsonPath("$[0].amount.amount").value("10000.00"))
            .andExpect(jsonPath("$[0].reversesAccrualId").doesNotExist());
    }

    // ============================================================================================
    // PUT /agents/{agentId}/commission-rate, GET /commission-accruals
    // ============================================================================================

    /** A per-lender rate, typed as a percentage and stored as the fraction the calculator uses. */
    @Test
    void setCommissionRateReturns200WithThePlanNowAttached() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID productId = createActiveProduct(tenantId, "DIST-CT-RATE");
        Agent agent = onboardAgent(tenantId, "RATE-200", null);

        mockMvc.perform(put("/agents/{agentId}/commission-rate", agent.agentId())
                .with(financeStaffOf(tenantId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"" + productId + "\",\"ratePercent\":\"12.5\"}"))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.rules[0].tierType").value("FIRST_YEAR"))
            .andExpect(jsonPath("$.rules[0].rate").value("0.1250"));

        // And it is the plan that now applies to this agent.
        mockMvc.perform(get("/agents/{agentId}/commission-plan", agent.agentId())
                .queryParam("productId", productId.toString())
                .with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rules[0].rate").value("0.1250"));
    }

    /** Deciding how much commission is owed is finance's, not any staff member's. */
    @Test
    void setCommissionRateReturns403ForAnUnderwriter() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID productId = createActiveProduct(tenantId, "DIST-CT-RATE-403");
        Agent agent = onboardAgent(tenantId, "RATE-403", null);

        mockMvc.perform(put("/agents/{agentId}/commission-rate", agent.agentId())
                .with(underwriterStaffOf(tenantId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"" + productId + "\",\"ratePercent\":\"10\"}"))
            .andExpect(status().isForbidden())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void listAccrualsForAPolicyReturnsWhatItHasEarned() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID productId = createActiveProduct(tenantId, "DIST-CT-POLACCR");
        createPlan(tenantId, productId);
        Agent agent = onboardAgent(tenantId, "POLACCR", null);
        UUID statementId = statementFor(tenantId, productId, agent.agentId(), "POLACCR");
        TenantContext.set(tenantId);
        String policyNumber = distributionApi.listAccruals(statementId).get(0).policyNumber();
        TenantContext.clear();

        mockMvc.perform(get("/commission-accruals").queryParam("policyNumber", policyNumber)
                .with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "CommissionAccrualView"))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].policyNumber").value(policyNumber))
            .andExpect(jsonPath("$[0].agentId").value(agent.agentId().toString()));
    }

    // ============================================================================================
    // POST /commission-plans
    // ============================================================================================

    @Test
    void createCommissionPlanReturns201() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID productId = createActiveProduct(tenantId, "DIST-CT-CREATE");

        mockMvc.perform(post("/commission-plans").with(financeStaffOf(tenantId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productId":"%s","rules":[{"tierType":"FIRST_YEAR","rate":"0.10"}]}
                    """.formatted(productId)))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "CommissionPlanView"))
            .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void createCommissionPlanReturns422ForAThresholdBonusRule() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID productId = createActiveProduct(tenantId, "DIST-CT-TB");

        mockMvc.perform(post("/commission-plans").with(financeStaffOf(tenantId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productId":"%s","rules":[{"tierType":"THRESHOLD_BONUS","rate":"0.10"}]}
                    """.formatted(productId)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("DISTRIBUTION_VALIDATION_FAILED"));
    }

    @Test
    void createCommissionPlanReturns422ForARuleWithBothRateAndFlatAmount() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID productId = createActiveProduct(tenantId, "DIST-CT-BOTH");

        mockMvc.perform(post("/commission-plans").with(financeStaffOf(tenantId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productId":"%s","rules":[{"tierType":"FIRST_YEAR","rate":"0.10",
                     "flatAmount":{"amount":"5000.00","currencyCode":"TZS"}}]}
                    """.formatted(productId)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errorCode").value("DISTRIBUTION_VALIDATION_FAILED"));
    }

    @Test
    void createCommissionPlanReturns403ForStaffWithoutTheFinanceRole() throws Exception {
        UUID tenantId = UUID.randomUUID();
        // A REAL product with an active version, so a broken gate returns 201, not a 404.
        UUID productId = createActiveProduct(tenantId, "DIST-CT-403");

        mockMvc.perform(post("/commission-plans").with(underwriterStaffOf(tenantId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productId":"%s","rules":[{"tierType":"FIRST_YEAR","rate":"0.10"}]}
                    """.formatted(productId)))
            .andExpect(status().isForbidden());
    }

    // ============================================================================================
    // POST .../payout
    // ============================================================================================

    @Test
    void requestPayoutReturns202ForAClosedStatement() throws Exception {
        wireMock.stubFor(WireMock.post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"ACCEPTED\",\"gatewayReference\":\"MM-CT-PAYOUT\"}")));
        UUID tenantId = UUID.randomUUID();
        UUID productId = createActiveProduct(tenantId, "DIST-CT-PAYOUT");
        createPlan(tenantId, productId);
        Agent agent = onboardAgent(tenantId, "PAYOUT-202", null);
        UUID statementId = statementFor(tenantId, productId, agent.agentId(), "PAYOUT-202");
        closeStatement(tenantId, statementId);

        mockMvc.perform(post("/agents/{agentId}/commission-statements/{statementId}/payout",
                    agent.agentId(), statementId)
                .with(financeStaffOf(tenantId))
                .header("Idempotency-Key", "ct-payout-" + statementId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payeeRef\":\"MPESA-0715000001\"}"))
            .andExpect(status().isAccepted())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));

        // The chain behind the 202 really ran -- without this the endpoint would answer 202 even
        // with payment entirely broken, since its listener swallows its own failures.
        wireMock.verify(exactly(1), postRequestedFor(urlPathEqualTo("/disburse")));
    }

    @Test
    void requestPayoutReturns409ForAStatementStillOpen() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID productId = createActiveProduct(tenantId, "DIST-CT-409");
        createPlan(tenantId, productId);
        Agent agent = onboardAgent(tenantId, "PAYOUT-409", null);
        UUID statementId = statementFor(tenantId, productId, agent.agentId(), "PAYOUT-409");
        // Deliberately NOT closed.

        mockMvc.perform(post("/agents/{agentId}/commission-statements/{statementId}/payout",
                    agent.agentId(), statementId)
                .with(financeStaffOf(tenantId))
                .header("Idempotency-Key", "ct-payout-" + statementId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payeeRef\":\"MPESA-0715000002\"}"))
            .andExpect(status().isConflict())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("DISTRIBUTION_INVALID_STATE"));

        wireMock.verify(exactly(0), postRequestedFor(urlPathEqualTo("/disburse")));
    }

    @Test
    void requestPayoutReturns400WhenTheIdempotencyKeyIsMissing() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID productId = createActiveProduct(tenantId, "DIST-CT-PAYOUT-400");
        createPlan(tenantId, productId);
        Agent agent = onboardAgent(tenantId, "PAYOUT-400", null);
        UUID statementId = statementFor(tenantId, productId, agent.agentId(), "PAYOUT-400");
        closeStatement(tenantId, statementId);

        // Spec-invalid request (the header is declared required), so no isValid matcher here.
        mockMvc.perform(post("/agents/{agentId}/commission-statements/{statementId}/payout",
                    agent.agentId(), statementId)
                .with(financeStaffOf(tenantId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payeeRef\":\"MPESA-0715000003\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        // The statement must be untouched and the rail unreached -- a 400 that had already moved
        // the statement to PAYOUT_REQUESTED would be worse than no validation at all.
        wireMock.verify(exactly(0), postRequestedFor(urlPathEqualTo("/disburse")));
    }

    /**
     * Same consistency fix as {@code listAccrualsReturns404WhenTheStatementBelongsToADifferentAgent}:
     * the path's {@code agentId} and {@code statementId} must actually go together, or a
     * fat-fingered {@code agentId} would silently pay out a different agent's money.
     */
    @Test
    void requestPayoutReturns404WhenTheStatementBelongsToADifferentAgent() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID productId = createActiveProduct(tenantId, "DIST-CT-PAYOUT-IDOR");
        createPlan(tenantId, productId);
        Agent owner = onboardAgent(tenantId, "PAYOUT-IDOR-OWNER", null);
        Agent other = onboardAgent(tenantId, "PAYOUT-IDOR-OTHER", null);
        UUID ownersStatementId = statementFor(tenantId, productId, owner.agentId(), "PAYOUT-IDOR-OWNER");
        closeStatement(tenantId, ownersStatementId);

        mockMvc.perform(post("/agents/{agentId}/commission-statements/{statementId}/payout",
                    other.agentId(), ownersStatementId)
                .with(financeStaffOf(tenantId))
                .header("Idempotency-Key", "ct-payout-idor-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payeeRef\":\"MPESA-0715000099\"}"))
            .andExpect(status().isNotFound());

        // And the owner's statement must be untouched -- still CLOSED and payable, not silently
        // consumed by the mismatched request.
        TenantContext.set(tenantId);
        assertThat(commissionStatementRepository.findByStatementIdAndTenantId(ownersStatementId, tenantId)
            .orElseThrow().getStatus()).isEqualTo(StatementStatus.CLOSED);
        TenantContext.clear();
    }

    @Test
    void requestPayoutReturns403ForAnAgentToken() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID productId = createActiveProduct(tenantId, "DIST-CT-PAYOUT-403");
        createPlan(tenantId, productId);
        Agent agent = onboardAgent(tenantId, "PAYOUT-403", null);
        UUID statementId = statementFor(tenantId, productId, agent.agentId(), "PAYOUT-403");
        closeStatement(tenantId, statementId);

        // The agent's OWN statement, genuinely payable -- so this 403 is about the role gate, not
        // about the resource being missing or in the wrong state. An agent must not pay itself.
        mockMvc.perform(post("/agents/{agentId}/commission-statements/{statementId}/payout",
                    agent.agentId(), statementId)
                .with(agentOf(tenantId, agent.partyId()))
                .header("Idempotency-Key", "ct-payout-" + statementId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payeeRef\":\"MPESA-0715000004\"}"))
            .andExpect(status().isForbidden());

        wireMock.verify(exactly(0), postRequestedFor(urlPathEqualTo("/disburse")));
    }

    // --- suspend/reactivate: AgentProfile.setLicenseStatus has existed since M7 with no caller
    // anywhere on the platform -- these are the falsifiable proof that the real gap is closed. ---

    @Test
    void suspendAgentMatchesOpenApiContractAndTransitionsToSuspended() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Agent agent = onboardAgent(tenantId, "SUSPEND-OK", null);

        mockMvc.perform(post("/agents/{agentId}/suspend", agent.agentId()).with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.licenseStatus").value("SUSPENDED"));
    }

    @Test
    void suspendAgentRejectsAnAlreadySuspendedAgentWith409() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Agent agent = onboardAgent(tenantId, "SUSPEND-409", null);
        mockMvc.perform(post("/agents/{agentId}/suspend", agent.agentId()).with(financeStaffOf(tenantId)))
            .andExpect(status().isOk());

        mockMvc.perform(post("/agents/{agentId}/suspend", agent.agentId()).with(financeStaffOf(tenantId)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errorCode").value("DISTRIBUTION_INVALID_STATE"));
    }

    @Test
    void suspendAgentRejectsStaffWithoutTheFinanceRoleWith403() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Agent agent = onboardAgent(tenantId, "SUSPEND-403", null);

        mockMvc.perform(post("/agents/{agentId}/suspend", agent.agentId()).with(underwriterStaffOf(tenantId)))
            .andExpect(status().isForbidden());
    }

    @Test
    void reactivateAgentMatchesOpenApiContractAndTransitionsBackToActive() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Agent agent = onboardAgent(tenantId, "REACTIVATE-OK", null);
        mockMvc.perform(post("/agents/{agentId}/suspend", agent.agentId()).with(financeStaffOf(tenantId)))
            .andExpect(status().isOk());

        mockMvc.perform(post("/agents/{agentId}/reactivate", agent.agentId()).with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.licenseStatus").value("ACTIVE"));
    }

    @Test
    void reactivateAgentRejectsAnAlreadyActiveAgentWith409() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Agent agent = onboardAgent(tenantId, "REACTIVATE-409", null);

        mockMvc.perform(post("/agents/{agentId}/reactivate", agent.agentId()).with(financeStaffOf(tenantId)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errorCode").value("DISTRIBUTION_INVALID_STATE"));
    }
}
