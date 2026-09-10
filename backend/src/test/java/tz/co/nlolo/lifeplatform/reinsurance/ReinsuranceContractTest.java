package tz.co.nlolo.lifeplatform.reinsurance;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.SpecTypeConformance;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.claims.api.ClaimType;
import tz.co.nlolo.lifeplatform.claims.api.ClaimsApi;
import tz.co.nlolo.lifeplatform.claims.api.DeathClaimDetails;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.product.api.BenefitType;
import tz.co.nlolo.lifeplatform.product.api.FactorType;
import tz.co.nlolo.lifeplatform.product.api.IfrsMeasurementModel;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.ProductCategory;
import tz.co.nlolo.lifeplatform.product.api.ProductSnapshotView;
import tz.co.nlolo.lifeplatform.product.api.ProductSummaryView;
import tz.co.nlolo.lifeplatform.reinsurance.api.ReinsuranceApi;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyType;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// Deliberately NOT a wildcard import: WireMock.post(String) and MockMvcRequestBuilders.post(String)
// collide, and this class needs both -- WireMock's stub builder stays qualified as WireMock.post(...),
// mirroring DistributionContractTest's exact discipline.
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 9: HTTP-level contract coverage for {@code TreatyController}/{@code RecoveryController}
 * against {@code api/openapi/openapi-reinsurance.yaml}. Follows {@code DistributionContractTest}'s
 * structure: {@code @Testcontainers} + {@code @AutoConfigureMockMvc} + {@code @SpringBootTest} +
 * {@code jwt()} post-processors + {@code openApi().isValid(SPEC_PATH)} <b>paired with</b>
 * {@link SpecTypeConformance#matchesDeclaredTypes} on every response carrying a decimal.
 *
 * <p>Like {@code DistributionContractTest}, this class runs against the Testcontainers Postgres
 * <b>superuser</b>, not {@code app_role} -- RLS/grant coverage is {@code AppRolePrivilegesIntegrationTest}
 * and {@code RowLevelSecurityIntegrationTest}'s job, not this one's.
 *
 * <p>Cession and recovery rows cannot be created directly: {@code ReinsuranceApi} exposes no create
 * endpoint for either (see its own javadoc -- both are event-driven). So every fixture that needs a
 * real cession or recovery drives the actual chain: {@code PolicyApi.issuePolicy} -&gt;
 * {@code policy.PolicyIssued} -&gt; {@code reinsurance.application.PolicyEventListener} for cessions,
 * and the full {@code ClaimsApi.registerClaim}/{@code submitAssessment}/{@code decideSettlement} -&gt;
 * payment (via an in-process WireMock mobile-money stub, mirroring {@code RecoveryEndToEndTest}) -&gt;
 * {@code claims.ClaimSettled} -&gt; {@code reinsurance.application.ClaimEventListener} chain for
 * recoveries. All of it runs synchronously (AFTER_COMMIT, same thread), so no await/sleep anywhere
 * here.
 *
 * <p>Every 403/404 test seeds a real, valid target first, so a broken {@code @PreAuthorize} or
 * ownership/IDOR check surfaces as 2xx rather than an incidental 404/403 that would pass for the
 * wrong reason.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ReinsuranceContractTest {

    private static final String SPEC_PATH = "api/openapi/openapi-reinsurance.yaml";
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
            "db-migrations/party/V1__create_party_schema.sql",
            "db-migrations/party/V2__individual_person_record.sql",
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/product/V2__base_rate_table.sql",
            "db-migrations/product/V3__base_rate_structured_age.sql",
            "db-migrations/product/V4__rating_table_unique_band.sql",
            "db-migrations/product/V5__rating_table_age_bounds.sql",
            "db-migrations/product/V6__eligibility_bounds.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/underwriting/V2__agent_of_record.sql",
            "db-migrations/underwriting/V3__medical_disclosure_recorded_by.sql",
            "db-migrations/underwriting/V4__proposal_identity.sql",
            "db-migrations/underwriting/V5__explicit_decision.sql",
            "db-migrations/underwriting/V6__proposal_terms_and_beneficiaries.sql",
            "db-migrations/underwriting/V8__rating_multiplier.sql",
            "db-migrations/policy/V1__create_policy_schema.sql",
            "db-migrations/policy/V2__endorsement_append_only_and_money_checks.sql",
            "db-migrations/policy/V3__premium_fields.sql",
            "db-migrations/policy/V4__underwriting_case_id.sql",
            "db-migrations/policy/V5__beneficiary_party_index.sql",
            "db-migrations/policy/V6__policy_term.sql",
            "db-migrations/policy/V7__life_assured.sql",
            "db-migrations/policy/V10__one_policy_per_underwriting_case.sql",
            "db-migrations/policy/V11__not_taken_up_status.sql",
            "db-migrations/reinsurance/V1__create_reinsurance_schema.sql",
            "db-migrations/reinsurance/V2__grants_rls_money_checks_reinsurer_and_projection.sql",
            "db-migrations/claims/V1__create_claims_schema.sql",
            "db-migrations/claims/V2__grants_rls_money_checks_evidence_and_settlement_columns.sql",
            "db-migrations/claims/V3__registration_idempotency_key.sql",
            "db-migrations/payment/V1__create_payment_schema.sql",
            "db-migrations/payment/V2__grants_rls_money_checks_version_and_tenant_scoped_registries.sql");
    }

    @AfterAll
    static void stopGateway() { wireMock.stop(); }

    @Autowired private MockMvc mockMvc;
    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private PolicyApi policyApi;
    @Autowired private ClaimsApi claimsApi;
    @Autowired private ReinsuranceApi reinsuranceApi;

    @AfterEach
    void resetAfterEach() {
        TenantContext.clear();
        wireMock.resetAll();
    }

    // --- token shapes -------------------------------------------------------------------------

    /** FINANCE_OFFICER/ADMIN is the decision {@code TreatyController}'s javadoc records -- there is
     * no reinsurance-specific staff role, mirroring M7's identical decision for distribution. */
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

    private record Fixture(UUID applicantId, UUID productId, UUID productVersionId) {}

    /** Mirrors {@code RecoveryEndToEndTest.buildFixture}. */
    private Fixture buildFixture(UUID tenantId, String productCode) {
        TenantContext.set(tenantId);
        PartyView applicant = partyApi.registerIndividual("Reinsurance Contract Applicant " + productCode,
            LocalDate.of(1985, 3, 1), "+25571900" + String.format("%04d", Math.abs(productCode.hashCode() % 10000)),
            null, "test-agent");
        ProductSummaryView product = productApi.createProduct(productCode, "Reinsurance Contract Product",
            ProductCategory.TERM_LIFE, CURRENCY, "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        TenantContext.clear();
        return new Fixture(applicant.partyId(), product.productId(), snapshot.productVersionId());
    }

    /** Sum assured fixed at 2,000,000 -- every scenario settles the DEATH claim for the full sum
     * assured, so recoverable/ceded amounts fall out of clean arithmetic. */
    private String issuePolicy(UUID tenantId, Fixture fixture) {
        TenantContext.set(tenantId);
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(fixture.applicantId(), fixture.productId(),
            fixture.productVersionId(), new BigDecimal("2000000"), CURRENCY, new BigDecimal("100000.00"), CURRENCY,
            "MONTHLY", null, List.of(), "Reinsurance contract test");
        String policyNumber = policyApi.issuePolicy(null, request, "test-staff").policyNumber();
        // Cover starts with the first premium. This fixture needs a policy on risk.
        policyApi.activateOnFirstPremium(policyNumber);
        TenantContext.clear();
        return policyNumber;
    }

    private UUID createTreaty(UUID tenantId, TreatyType type, BigDecimal retention, BigDecimal cessionPercent) {
        TenantContext.set(tenantId);
        UUID treatyId = reinsuranceApi.createTreaty(new ReinsuranceApi.CreateTreatyRequest(
            "Africa Re", type, retention, CURRENCY, cessionPercent, LocalDate.now().minusMonths(1), null),
            "finance-officer").treatyId();
        TenantContext.clear();
        return treatyId;
    }

    /** Registers, assesses (for the full 2,000,000 sum assured), and returns a fresh DEATH claim
     * id -- mirrors {@code RecoveryEndToEndTest}'s helper of the same shape. */
    private UUID registerAndAssessDeathClaim(UUID tenantId, Fixture fixture, String policyNumber, String regKey,
                                              String assessor) {
        TenantContext.set(tenantId);
        ClaimsApi.RegisterClaimRequest request = new ClaimsApi.RegisterClaimRequest(policyNumber,
            fixture.applicantId(), ClaimType.DEATH, LocalDate.now().minusDays(1),
            new DeathClaimDetails("Natural causes", "Dar es Salaam", LocalDate.now().minusDays(1), "Dr. Test"));
        UUID claimId = claimsApi.registerClaim(request, regKey, "claims-staff").claimId();
        claimsApi.submitAssessment(claimId, "Consistent with cause of death", new BigDecimal("2000000"), CURRENCY,
            false, assessor);
        TenantContext.clear();
        return claimId;
    }

    /** Approves settlement for the full 2,000,000 sum assured and drives the real chain (WireMock
     * ACCEPT) all the way through payment's confirmation into {@code claims.ClaimSettled}, which
     * {@code reinsurance.application.ClaimEventListener} consumes synchronously. */
    private void settleClaim(UUID tenantId, UUID claimId, String payeeRef, String settleKey, String approver) {
        wireMock.stubFor(WireMock.post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"ACCEPTED\",\"gatewayReference\":\"MM-" + settleKey + "\"}")));
        TenantContext.set(tenantId);
        claimsApi.decideSettlement(claimId, true, new BigDecimal("2000000"), CURRENCY, null, payeeRef, settleKey,
            approver);
        TenantContext.clear();
    }

    /** Builds a full policy -&gt; 50% QUOTA_SHARE cession -&gt; DEATH claim -&gt; settlement chain,
     * leaving exactly one real, confirmable recovery behind. Returns the claim id; the recovery id
     * is read back via {@link ReinsuranceApi#listRecoveriesForClaim}. */
    private UUID recoveryFixture(UUID tenantId, String tag) {
        Fixture fixture = buildFixture(tenantId, "RI-CT-" + tag);
        createTreaty(tenantId, TreatyType.QUOTA_SHARE, new BigDecimal("0.00"), new BigDecimal("50.00"));
        String policyNumber = issuePolicy(tenantId, fixture);
        UUID claimId = registerAndAssessDeathClaim(tenantId, fixture, policyNumber, "ri-ct-reg-" + tag,
            "assessor-" + tag);
        settleClaim(tenantId, claimId, "MPESA-0716" + String.format("%06d", Math.abs(tag.hashCode() % 1000000)),
            "ri-ct-settle-" + tag, "manager-" + tag);
        return claimId;
    }

    private UUID recoveryIdFor(UUID tenantId, UUID claimId) {
        TenantContext.set(tenantId);
        UUID recoveryId = reinsuranceApi.listRecoveriesForClaim(claimId).get(0).recoveryId();
        TenantContext.clear();
        return recoveryId;
    }

    private static String quotaShareTreatyBody(String reinsurerName, String cessionPercent, LocalDate effectiveFrom) {
        return """
            {"reinsurerName":"%s","treatyType":"QUOTA_SHARE","retentionLimit":{"amount":"0.00","currencyCode":"TZS"},
             "cessionPercent":"%s","effectiveFrom":"%s"}
            """.formatted(reinsurerName, cessionPercent, effectiveFrom);
    }

    // ============================================================================================
    // POST /treaties
    // ============================================================================================

    @Test
    void createTreatyReturns201ForAValidQuotaShare() throws Exception {
        UUID tenantId = UUID.randomUUID();

        mockMvc.perform(post("/treaties").with(financeStaffOf(tenantId))
                .header("Idempotency-Key", "ct-treaty-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(quotaShareTreatyBody("Africa Re", "30.00", LocalDate.now().minusMonths(1))))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "TreatyView"))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.cessionPercent").value("30.00"));
    }

    @Test
    void createTreatyReturns422ForAQuotaShareTreatyWithNoCessionPercent() throws Exception {
        UUID tenantId = UUID.randomUUID();

        mockMvc.perform(post("/treaties").with(financeStaffOf(tenantId))
                .header("Idempotency-Key", "ct-treaty-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"reinsurerName":"Africa Re","treatyType":"QUOTA_SHARE",
                     "retentionLimit":{"amount":"0.00","currencyCode":"TZS"},
                     "effectiveFrom":"%s"}
                    """.formatted(LocalDate.now().minusMonths(1))))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("REINSURANCE_VALIDATION_FAILED"));
    }

    @Test
    void createTreatyReturns422ForASurplusTreatyCarryingACessionPercent() throws Exception {
        UUID tenantId = UUID.randomUUID();

        mockMvc.perform(post("/treaties").with(financeStaffOf(tenantId))
                .header("Idempotency-Key", "ct-treaty-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"reinsurerName":"Africa Re","treatyType":"SURPLUS",
                     "retentionLimit":{"amount":"500000.00","currencyCode":"TZS"},
                     "cessionPercent":"30.00","effectiveFrom":"%s"}
                    """.formatted(LocalDate.now().minusMonths(1))))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("REINSURANCE_VALIDATION_FAILED"));
    }

    /**
     * Deliberate deviation from a literal 422 here. {@code CreateTreatyRequestDto.reinsurerName} is
     * annotated {@code @NotBlank} (unlike {@code cessionPercent}, whose QUOTA_SHARE/SURPLUS pairing
     * is explicitly documented as NOT a bean-validation constraint, left entirely to
     * {@code ReinsuranceApiImpl}'s 422). A blank name therefore fails Jakarta Bean Validation before
     * the request ever reaches {@code ReinsuranceApiImpl.createTreaty} -- {@code
     * GlobalExceptionHandler.handleMethodArgumentNotValid} maps that to 400 VALIDATION_ERROR, not
     * 422, so the service's own "A reinsurer name is required" branch is genuinely unreachable via
     * HTTP (it IS reached by {@code ReinsuranceApiIntegrationTest.rejectsABlankReinsurerName}, which
     * calls {@code ReinsuranceApi} directly). This test asserts the real, reachable status rather
     * than the brief's literal guess. Final review (M2) corrected openapi-reinsurance.yaml's 422
     * description, which used to list "blank" as a cause alongside "too long" despite blank being
     * unreachable via HTTP; it now says a blank name is a 400, matching this test.
     */
    @Test
    void createTreatyReturns400ForABlankReinsurerName() throws Exception {
        UUID tenantId = UUID.randomUUID();

        mockMvc.perform(post("/treaties").with(financeStaffOf(tenantId))
                .header("Idempotency-Key", "ct-treaty-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(quotaShareTreatyBody("   ", "30.00", LocalDate.now().minusMonths(1))))
            .andExpect(status().isBadRequest())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void createTreatyReturns403ForStaffWithoutTheFinanceRole() throws Exception {
        UUID tenantId = UUID.randomUUID();
        // The body is a genuinely valid QUOTA_SHARE request, so a broken @PreAuthorize would
        // return 201, not an incidental 4xx.
        mockMvc.perform(post("/treaties").with(underwriterStaffOf(tenantId))
                .header("Idempotency-Key", "ct-treaty-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(quotaShareTreatyBody("Africa Re", "30.00", LocalDate.now().minusMonths(1))))
            .andExpect(status().isForbidden());
    }

    @Test
    void createTreatyReturns403ForAnAgentToken() throws Exception {
        UUID tenantId = UUID.randomUUID();

        mockMvc.perform(post("/treaties").with(agentOf(tenantId, UUID.randomUUID()))
                .header("Idempotency-Key", "ct-treaty-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(quotaShareTreatyBody("Africa Re", "30.00", LocalDate.now().minusMonths(1))))
            .andExpect(status().isForbidden());
    }

    @Test
    void createTreatyReturns400WhenTheIdempotencyKeyIsMissing() throws Exception {
        UUID tenantId = UUID.randomUUID();

        // No isValid matcher: the spec declares Idempotency-Key required, so this request is
        // deliberately spec-invalid and isValid validates requests too. Status + errorCode only.
        mockMvc.perform(post("/treaties").with(financeStaffOf(tenantId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(quotaShareTreatyBody("Africa Re", "30.00", LocalDate.now().minusMonths(1))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    // ============================================================================================
    // GET /treaties/{treatyId}
    // ============================================================================================

    @Test
    void getTreatyReturns200ForStaff() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID treatyId = createTreaty(tenantId, TreatyType.QUOTA_SHARE, new BigDecimal("0.00"), new BigDecimal("30.00"));

        mockMvc.perform(get("/treaties/{treatyId}", treatyId).with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "TreatyView"))
            .andExpect(jsonPath("$.treatyId").value(treatyId.toString()));
    }

    @Test
    void getTreatyReturns404ForAnUnknownId() throws Exception {
        UUID tenantId = UUID.randomUUID();

        mockMvc.perform(get("/treaties/{treatyId}", UUID.randomUUID()).with(financeStaffOf(tenantId)))
            .andExpect(status().isNotFound())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("TREATY_NOT_FOUND"));
    }

    @Test
    void getTreatyReturns404NotForbiddenForATreatyInAnotherTenant() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID treatyId = createTreaty(tenantA, TreatyType.QUOTA_SHARE, new BigDecimal("0.00"), new BigDecimal("30.00"));

        // 404, never 403: a 403 would confirm to tenant B that this id exists somewhere.
        mockMvc.perform(get("/treaties/{treatyId}", treatyId).with(financeStaffOf(tenantB)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("TREATY_NOT_FOUND"));
    }

    // ============================================================================================
    // GET /treaties
    // ============================================================================================

    @Test
    void listTreatiesReturns200AndTheStatusFilterGenuinelyNarrows() throws Exception {
        UUID tenantId = UUID.randomUUID();
        createTreaty(tenantId, TreatyType.QUOTA_SHARE, new BigDecimal("0.00"), new BigDecimal("30.00"));

        mockMvc.perform(get("/treaties").with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "TreatyView"))
            .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(get("/treaties").param("status", "ACTIVE").with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));

        // The falsifiable half -- a filter that ignored its argument would still return the row.
        mockMvc.perform(get("/treaties").param("status", "EXPIRED").with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    // ============================================================================================
    // GET /policies/{policyNumber}/cessions
    // ============================================================================================

    @Test
    void listCessionsForPolicyReturns200WithTheArray() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "RI-CT-CESSION");
        createTreaty(tenantId, TreatyType.QUOTA_SHARE, new BigDecimal("0.00"), new BigDecimal("50.00"));
        String policyNumber = issuePolicy(tenantId, fixture);

        mockMvc.perform(get("/policies/{policyNumber}/cessions", policyNumber).with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "CessionView"))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].policyNumber").value(policyNumber))
            .andExpect(jsonPath("$[0].cededAmount.amount").value("1000000.00"));
    }

    // ============================================================================================
    // GET /claims/{claimId}/recoveries
    // ============================================================================================

    @Test
    void listRecoveriesForClaimReturns200WithTheArray() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID claimId = recoveryFixture(tenantId, "LIST");

        mockMvc.perform(get("/claims/{claimId}/recoveries", claimId).with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "ClaimRecoveryView"))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].claimId").value(claimId.toString()))
            .andExpect(jsonPath("$[0].recoverableAmount.amount").value("1000000.00"))
            .andExpect(jsonPath("$[0].confirmedAt").doesNotExist());
    }

    // ============================================================================================
    // POST /claims/{claimId}/recoveries/{recoveryId}/confirm
    // ============================================================================================

    @Test
    void confirmRecoveryReturns202AndThenReturns409OnASecondConfirm() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID claimId = recoveryFixture(tenantId, "CONFIRM");
        UUID recoveryId = recoveryIdFor(tenantId, claimId);

        mockMvc.perform(post("/claims/{claimId}/recoveries/{recoveryId}/confirm", claimId, recoveryId)
                .with(financeStaffOf(tenantId))
                .header("Idempotency-Key", "ct-confirm-" + UUID.randomUUID()))
            .andExpect(status().isAccepted())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "ClaimRecoveryView"))
            .andExpect(jsonPath("$.recoveryId").value(recoveryId.toString()))
            .andExpect(jsonPath("$.confirmedAt").exists());

        // The repeat confirm: ClaimRecovery.confirm throws InvalidRecoveryStateException on a
        // non-genuine transition, mapped to 409 -- this is the regression guard against a second
        // reinsurance.RecoveryConfirmed (M6's I1 finding, a duplicate finaccounting journal entry).
        mockMvc.perform(post("/claims/{claimId}/recoveries/{recoveryId}/confirm", claimId, recoveryId)
                .with(financeStaffOf(tenantId))
                .header("Idempotency-Key", "ct-confirm-" + UUID.randomUUID()))
            .andExpect(status().isConflict())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("REINSURANCE_INVALID_STATE"));
    }

    /**
     * Regression test for {@code RecoveryController.confirmRecovery}'s IDOR fix: the path nests
     * {@code recoveryId} under {@code claimId}, but {@code ReinsuranceApi.confirmRecovery} takes no
     * {@code claimId} at all -- it is a pure, tenant-scoped, claim-agnostic recovery-id lookup.
     * Seeds TWO real claims, each with its own real recovery (never an outright-nonexistent id,
     * which would 404 even with the IDOR bug present and prove nothing) so a broken check that
     * trusts the path nesting would return 202 here rather than an incidental 404.
     */
    @Test
    void confirmRecoveryReturns404WhenTheRecoveryBelongsToADifferentClaim() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID claimA = recoveryFixture(tenantId, "IDOR-A");
        UUID claimB = recoveryFixture(tenantId, "IDOR-B");
        UUID recoveryIdB = recoveryIdFor(tenantId, claimB);

        mockMvc.perform(post("/claims/{claimId}/recoveries/{recoveryId}/confirm", claimA, recoveryIdB)
                .with(financeStaffOf(tenantId))
                .header("Idempotency-Key", "ct-confirm-idor-" + UUID.randomUUID()))
            .andExpect(status().isNotFound())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("RECOVERY_NOT_FOUND"));

        // And claim B's recovery must be untouched -- still unconfirmed, not silently consumed by
        // the mismatched request against claim A's path.
        TenantContext.set(tenantId);
        assertThat(reinsuranceApi.listRecoveriesForClaim(claimB).get(0).confirmedAt()).isNull();
        TenantContext.clear();
    }

    @Test
    void confirmRecoveryReturns400WhenTheIdempotencyKeyIsMissing() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID claimId = recoveryFixture(tenantId, "NOKEY");
        UUID recoveryId = recoveryIdFor(tenantId, claimId);

        // Spec-invalid request (the header is declared required), so no isValid matcher here.
        mockMvc.perform(post("/claims/{claimId}/recoveries/{recoveryId}/confirm", claimId, recoveryId)
                .with(financeStaffOf(tenantId)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        // Must be untouched -- a 400 that had already confirmed the recovery would be worse than
        // no validation at all.
        TenantContext.set(tenantId);
        assertThat(reinsuranceApi.listRecoveriesForClaim(claimId).get(0).confirmedAt()).isNull();
        TenantContext.clear();
    }
}
