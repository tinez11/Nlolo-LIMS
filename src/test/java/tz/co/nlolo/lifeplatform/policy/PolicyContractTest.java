package tz.co.nlolo.lifeplatform.policy;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.product.api.*;
import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import com.jayway.jsonpath.JsonPath;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-level contract coverage for {@code PolicyController}, targeted at the review findings on
 * Task 4 (see .superpowers/sdd/2026-08-08-m3-policy-core/task-4-review.md, C1): a negative
 * control proving {@code replaceBeneficiaries} now runs Bean Validation on its bare-{@code List}
 * request body instead of silently skipping it.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class PolicyContractTest {

    private static final String SPEC_PATH = "api/openapi/openapi-policy.yaml";

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
            "db-migrations/product/V1__create_product_schema.sql",
            "db-migrations/underwriting/V1__create_underwriting_schema.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/policy/V1__create_policy_schema.sql",
            "db-migrations/audit/V1__create_audit_schema.sql");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private PolicyApi policyApi;

    /**
     * Issues a real, persisted policy directly through {@code PolicyApi} (bypassing HTTP, same
     * fixture idiom as {@code PolicyApiIntegrationTest}) so the HTTP-level test below has a real
     * policyNumber to target -- {@code replaceBeneficiaries} calls {@code policyApi.getPolicy}
     * before doing anything else, so a nonexistent policy would 404 before validation ever runs.
     */
    private String issueTestPolicy(UUID tenantId, String productCode) {
        TenantContext.set(tenantId);
        PartyView applicant = partyApi.registerIndividual("Policy Contract Test Applicant " + productCode, LocalDate.of(1990, 1, 1),
            "+25571310" + String.format("%04d", Math.abs(productCode.hashCode() % 10000)), null, "test-agent");
        ProductSummaryView product = productApi.createProduct(productCode, "Policy Contract Test Product", ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(applicant.partyId(), product.productId(), snapshot.productVersionId(),
            new BigDecimal("1000000"), "TZS", null, "MONTHLY", List.of(), "Contract test issuance");
        String policyNumber = policyApi.issuePolicy(UUID.randomUUID(), request, "test-staff").policyNumber();
        TenantContext.clear();
        return policyNumber;
    }

    // --- Task 5: full-HTTP fixture idiom (ProductContractTest / UnderwritingContractTest) -----
    //
    // Unlike issueTestPolicy above (which calls PolicyApi directly to fixture a policy for the
    // beneficiary-validation tests), the tests below exercise the *entire* policy REST surface,
    // including POST /policies/manual-issue itself -- so the fixture chain below goes through
    // real HTTP end to end (register applicant -> publish product -> open underwriting case ->
    // manual-issue). Only the final manual-issue hop is OpenAPI-validated here, against this
    // module's policy spec; the earlier registerApplicant/publishProduct/openUnderwritingCase
    // hops call no validator, correctly, since this file's SPEC_PATH is policy-only and doesn't
    // cover the party/product/underwriting modules' own contracts.

    private record IssuedPolicy(String policyNumber, UUID policyholderPartyId) {}

    private UUID registerApplicant(UUID tenantId, String phoneSuffix) throws Exception {
        String response = mockMvc.perform(post("/parties/individuals")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"Policy Contract Applicant","dateOfBirth":"1988-03-15","contactInfo":{"phoneNumber":"+25571234%s"}}
                    """.formatted(phoneSuffix)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.partyId"));
    }

    private record ProductFixture(UUID productId, UUID productVersionId) {}

    private ProductFixture publishProduct(UUID tenantId, String code) throws Exception {
        String createResponse = mockMvc.perform(post("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productCode":"%s","productName":"Policy Contract Product","category":"TERM_LIFE","defaultCurrency":"TZS"}
                    """.formatted(code)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String productId = JsonPath.read(createResponse, "$.productId");

        mockMvc.perform(post("/products/" + productId + "/versions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"ifrsMeasurementModel":"PAA","effectiveDate":"2026-01-01",
                     "ratingTable":[{"factorType":"AGE","band":"30-39","multiplier":1.0},{"factorType":"SUM_ASSURED_BAND","band":"LOW","multiplier":1.0}],
                     "benefitSchedule":[{"benefitType":"DEATH","calculationMethod":"SUM_ASSURED"}]}
                    """))
            .andExpect(status().isCreated());

        String snapshotResponse = mockMvc.perform(get("/products/" + productId + "/active-snapshot")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return new ProductFixture(UUID.fromString(productId), UUID.fromString(JsonPath.read(snapshotResponse, "$.productVersionId")));
    }

    private UUID openUnderwritingCase(UUID tenantId, UUID applicantId, ProductFixture product) throws Exception {
        String response = mockMvc.perform(post("/underwriting/cases")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"applicantPartyId":"%s","productId":"%s","productVersionId":"%s","sumAssured":{"amount":"1000000.00","currencyCode":"TZS"}}
                    """.formatted(applicantId, product.productId(), product.productVersionId())))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.caseId"));
    }

    private IssuedPolicy manualIssue(UUID tenantId, String productCode) throws Exception {
        UUID applicantId = registerApplicant(tenantId, String.valueOf(Math.abs(productCode.hashCode() % 10000)));
        ProductFixture product = publishProduct(tenantId, productCode);
        UUID caseId = openUnderwritingCase(tenantId, applicantId, product);

        String response = mockMvc.perform(post("/policies/manual-issue")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"underwritingCaseId":"%s","policyholderPartyId":"%s","productVersionId":"%s",
                     "sumAssured":{"amount":"1000000.00","currencyCode":"TZS"},"agentOfRecordId":"%s",
                     "reasonForManualIssue":"Contract test manual issuance"}
                    """.formatted(caseId, applicantId, product.productVersionId(), UUID.randomUUID())))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andReturn().getResponse().getContentAsString();
        return new IssuedPolicy(JsonPath.read(response, "$.policyNumber"), applicantId);
    }

    // --- Task 4 review C1: @Valid placement on replaceBeneficiaries's bare-List request body ---
    //
    // Before the fix, PolicyController.replaceBeneficiaries declared
    // `@RequestBody List<@Valid BeneficiaryInputDto> beneficiaries` -- @Valid on the generic type
    // argument, not the parameter itself. The review predicted this would let a null `type`
    // reach PolicyApiImpl.validateAndBuildBeneficiaries unchecked and NPE into a bare 500.
    // Empirically (verified by temporarily reverting this annotation and re-running this exact
    // test), that specific 500 does NOT reproduce on this codebase's actual Spring Boot 3.3.5 /
    // Framework 6.1 version: Framework 6.1's newer method-validation path (HandlerMethodValidator,
    // distinct from the classic WebDataBinder-based @Valid @RequestBody path the review describes)
    // finds the @Valid on the type argument and still cascades into list elements via
    // ExecutableValidator, so the pre-fix request already came back as HTTP 400 -- but as a
    // generic, undifferentiated ProblemDetail (`errorCode: BAD_REQUEST`, `detail: "Validation
    // failure"`, no per-field `errors[]`) via HandlerMethodValidationException, NOT the platform's
    // standard VALIDATION_ERROR shape with field-level detail that MethodArgumentNotValidException
    // produces for every other @Valid @RequestBody endpoint (manualIssue, applyEndorsement). The
    // fix is still correct and worth keeping -- canonical @Valid placement per the reviewer's
    // recommendation, and it demonstrably changes this endpoint's error contract to match the
    // rest of the platform (VALIDATION_ERROR + errors[] naming the failing field) instead of a
    // one-off generic shape a client can't reliably parse. This test asserts that consistent
    // shape and fails pre-fix on the errorCode/shape mismatch (not on status code, which was
    // already 400 either way) -- see task-4-report.md for the full before/after comparison.

    @Test
    void replaceBeneficiariesRejectsNullTypeWithBadRequestNotServerError() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String policyNumber = issueTestPolicy(tenantId, "POLICY-CONTRACT-BENE-01");

        // `type` deliberately omitted (JSON-absent, same effect as explicit null for a Jackson
        // record field with no default) -- @NotNull BeneficiaryInputDto.type must reject this
        // before PolicyController's body ever runs, not after.
        mockMvc.perform(put("/policies/" + policyNumber + "/beneficiaries")
                .with(jwt()
                    .authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    [{"sharePercent": 100}]
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.traceId").exists())
            .andExpect(jsonPath("$.errors[?(@.field == 'beneficiaries[0].type')]").exists());
    }

    @Test
    void replaceBeneficiariesAcceptsAValidPayloadViaRealHttp() throws Exception {
        // Sanity check alongside the negative control above: the @Valid fix must not reject
        // well-formed input. A single FREEFORM beneficiary summing to 100% is valid per
        // PolicyApiImpl.validateAndBuildBeneficiaries's exactly-one-of/100%-sum rules.
        UUID tenantId = UUID.randomUUID();
        String policyNumber = issueTestPolicy(tenantId, "POLICY-CONTRACT-BENE-02");

        mockMvc.perform(put("/policies/" + policyNumber + "/beneficiaries")
                .with(jwt()
                    .authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    [{"type": "FREEFORM", "freeformDesignee": "estate", "sharePercent": 100}]
                    """))
            .andExpect(status().isOk());
    }

    // --- Task 5: falsifiability gate for Tasks 2-4 -- the rest of the policy REST surface ------

    @Test
    void manualIssueRejectsNonStaffCaller() throws Exception {
        // The body below is a fully well-formed ManualIssueRequest (real UUIDs, valid Money,
        // non-blank reason) -- every field Bean Validation checks is satisfied, so this can only
        // be rejected by the @PreAuthorize("hasRole('REALM_STAFF')") role check under test, not
        // by @Valid failing first on an empty/malformed body (which would 400, not 403, and
        // would falsely "pass" a broken role check for the wrong reason).
        UUID tenantId = UUID.randomUUID();
        mockMvc.perform(post("/policies/manual-issue")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"underwritingCaseId":"%s","policyholderPartyId":"%s","productVersionId":"%s",
                     "sumAssured":{"amount":"1000000.00","currencyCode":"TZS"},"agentOfRecordId":null,
                     "reasonForManualIssue":"Should be rejected before reaching the service layer"}
                    """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())))
            .andExpect(status().isForbidden());
    }

    @Test
    void getPolicyMatchesOpenApiContractForStaff() throws Exception {
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy issued = manualIssue(tenantId, "POLICY-CONTRACT-01");

        mockMvc.perform(get("/policies/" + issued.policyNumber())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.sumAssured.amount").value("1000000.00"))
            .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void getPolicyRejectsCustomerReadingSomeoneElsesPolicy() throws Exception {
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy issued = manualIssue(tenantId, "POLICY-CONTRACT-02");

        mockMvc.perform(get("/policies/" + issued.policyNumber())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()).claim("party_id", UUID.randomUUID().toString()))))
            .andExpect(status().isForbidden());
    }

    @Test
    void getPolicyRejectsCrossTenantReadWithNotFoundForAntiEnumeration() throws Exception {
        // Same-tenant ownership mismatch (above) is a 403; a DIFFERENT tenant reading a policy
        // that isn't theirs must instead 404, not 403 -- leaking "this policyNumber exists (just
        // not for you)" via a 403 across tenant boundaries is the enumeration this guards
        // against. The customer's party_id claim below deliberately MATCHES the real
        // policyholder (issued.policyholderPartyId()) -- proving the 404 comes from
        // PolicyApiImpl.findPolicyOrThrow's tenant-scoped query (a different tenantId finds no
        // row) and not from the object-level ownership check, which this JWT would otherwise pass.
        UUID ownerTenantId = UUID.randomUUID();
        IssuedPolicy issued = manualIssue(ownerTenantId, "POLICY-CONTRACT-02B");
        UUID otherTenantId = UUID.randomUUID();

        mockMvc.perform(get("/policies/" + issued.policyNumber())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(builder -> builder.claim("tenant_id", otherTenantId.toString())
                        .claim("party_id", issued.policyholderPartyId().toString()))))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("POLICY_NOT_FOUND"));
    }

    @Test
    void getPolicyForNonexistentPolicyReturnsNotFoundNotServerError() throws Exception {
        mockMvc.perform(get("/policies/NOSUCHPOLICY1")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", UUID.randomUUID().toString()))))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("POLICY_NOT_FOUND"));
    }

    @Test
    void searchPoliciesMatchesOpenApiContractAndFindsTheSeededPolicy() throws Exception {
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy issued = manualIssue(tenantId, "POLICY-CONTRACT-03");

        mockMvc.perform(get("/policies")
                .queryParam("policyholderPartyId", issued.policyholderPartyId().toString())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.items[?(@.policyNumber == '" + issued.policyNumber() + "')]").exists());
    }

    @Test
    void applyEndorsementMatchesOpenApiContract() throws Exception {
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy issued = manualIssue(tenantId, "POLICY-CONTRACT-04");

        mockMvc.perform(post("/policies/" + issued.policyNumber() + "/endorsements")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"endorsementType":"ADDRESS_CHANGE","effectiveDate":"2026-01-15","changes":{"newAddress":"Dar es Salaam"}}
                    """))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void replaceBeneficiariesRejectsBothPartyIdAndFreeformDesigneeWith422() throws Exception {
        // Hand-written contract test (Global Constraints -- decision 13): openapi-policy.yaml's
        // BeneficiaryInput schema only declares required:[type, sharePercent], so a
        // schema-generated test would never send (or reject) both fields populated at once.
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy issued = manualIssue(tenantId, "POLICY-CONTRACT-06");

        mockMvc.perform(put("/policies/" + issued.policyNumber() + "/beneficiaries")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    [{"type":"PARTY","partyId":"%s","freeformDesignee":"estate","sharePercent":100,"revocable":true}]
                    """.formatted(issued.policyholderPartyId())))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("BENEFICIARY_VALIDATION_FAILED"));
    }

    @Test
    void replaceBeneficiariesRejectsSharesNotSummingTo100With422() throws Exception {
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy issued = manualIssue(tenantId, "POLICY-CONTRACT-07");

        mockMvc.perform(put("/policies/" + issued.policyNumber() + "/beneficiaries")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    [{"type":"FREEFORM","freeformDesignee":"estate","sharePercent":60,"revocable":true}]
                    """))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("BENEFICIARY_VALIDATION_FAILED"));
    }

    @Test
    void surrenderValueMatchesOpenApiContract() throws Exception {
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy issued = manualIssue(tenantId, "POLICY-CONTRACT-08");

        mockMvc.perform(get("/policies/" + issued.policyNumber() + "/surrender-value")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    @Test
    void surrenderPolicyReturns501WithChoreographyNotImplemented() throws Exception {
        // Decision 2: a real, routable, correctly-secured endpoint that returns 501, not 404
        // and not omitted -- this is the falsifiable proof of that decision, not prose. Both
        // paths now carry a documented 501 response in openapi-policy.yaml, so this validates
        // against the contract too.
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy issued = manualIssue(tenantId, "POLICY-CONTRACT-09");

        mockMvc.perform(post("/policies/" + issued.policyNumber() + "/surrender")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"payeeRef":"MPESA-0712345678"}
                    """))
            .andExpect(status().isNotImplemented())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("CHOREOGRAPHY_NOT_IMPLEMENTED"))
            .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void getProcessStatusReturns501WithChoreographyNotImplemented() throws Exception {
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy issued = manualIssue(tenantId, "POLICY-CONTRACT-10");

        mockMvc.perform(get("/policies/" + issued.policyNumber() + "/processes/" + UUID.randomUUID())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isNotImplemented())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("CHOREOGRAPHY_NOT_IMPLEMENTED"));
    }

    @Test
    void coverageStatusMatchesOpenApiContract() throws Exception {
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy issued = manualIssue(tenantId, "POLICY-CONTRACT-11");

        mockMvc.perform(get("/policies/" + issued.policyNumber() + "/coverage-status")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.activeCoverages[0].benefitType").value("DEATH"));
    }

    @Test
    void inForceMatchesOpenApiContractAndReturnsTrueForANewlyIssuedPolicy() throws Exception {
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy issued = manualIssue(tenantId, "POLICY-CONTRACT-12");

        mockMvc.perform(get("/policies/" + issued.policyNumber() + "/in-force")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.inForce").value(true));
    }
}
