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
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
            "db-migrations/policy/V1__create_policy_schema.sql",
            "db-migrations/policy/V2__endorsement_append_only_and_money_checks.sql",
            "db-migrations/policy/V3__premium_fields.sql",
            "db-migrations/policy/V4__underwriting_case_id.sql",
            "db-migrations/policy/V5__beneficiary_party_index.sql",
            "db-migrations/policy/V6__policy_term.sql",
            "db-migrations/policy/V7__life_assured.sql",
            "db-migrations/policy/V10__one_policy_per_underwriting_case.sql",
            "db-migrations/policy/V11__not_taken_up_status.sql",
            "db-migrations/policy/V8__group_policies_have_no_single_life_assured.sql",
            "db-migrations/policy/V9__group_scheme_and_members.sql",
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
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(applicant.partyId(), product.productId(), snapshot.productVersionId(),
            new BigDecimal("1000000"), "TZS", new BigDecimal("50000.00"), "TZS", "MONTHLY", null, List.of(), "Contract test issuance");
        String policyNumber = policyApi.issuePolicy(UUID.randomUUID(), request, "test-staff").policyNumber();
        // Cover starts with the first premium. This fixture needs a policy on risk.
        policyApi.activateOnFirstPremium(policyNumber);
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

    /**
     * Overload taking an explicit category: POLICY_SUSPENSION_ELIGIBLE_CATEGORIES
     * (refdata/V2) seeds only GROUP_LIFE as suspension-eligible -- the suspend/resume
     * tests below need a policy in that category, while every other fixture keeps
     * using plain TERM_LIFE.
     */
    private ProductFixture publishProduct(UUID tenantId, String code, String category) throws Exception {
        String createResponse = mockMvc.perform(post("/products")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"productCode":"%s","productName":"Policy Contract Product","category":"%s","defaultCurrency":"TZS"}
                    """.formatted(code, category)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String productId = JsonPath.read(createResponse, "$.productId");

        mockMvc.perform(post("/products/" + productId + "/versions")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"ifrsMeasurementModel":"PAA","effectiveDate":"2026-01-01",
                     "ratingTable":[{"factorType":"AGE","band":"30-39","multiplier":1.0,"ageFrom":30,"ageTo":39},{"factorType":"SUM_ASSURED_BAND","band":"LOW","multiplier":1.0}],
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
        return manualIssue(tenantId, productCode, "TERM_LIFE");
    }

    /**
     * A manually issued policy, in force.
     *
     * <p>Manual issue itself produces an OFFER, like every other issuance path -- see
     * {@link #manualIssueOffer} for that. Almost every contract test here goes on to suspend,
     * lapse, endorse or claim, all of which need cover, so this is the one that pays.
     */
    private IssuedPolicy manualIssue(UUID tenantId, String productCode, String category) throws Exception {
        IssuedPolicy issued = manualIssueOffer(tenantId, productCode, category);
        TenantContext.set(tenantId);
        policyApi.activateOnFirstPremium(issued.policyNumber());
        TenantContext.clear();
        return issued;
    }

    @Test
    void manualIssueIsRefusedWithoutAnIssuanceBasis() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID applicantId = registerApplicant(tenantId, "9101");
        ProductFixture product = publishProduct(tenantId, "POLICY-CONTRACT-BASIS-00", "TERM_LIFE");
        UUID caseId = openUnderwritingCase(tenantId, applicantId, product);

        // The whole point of the field. Manual issue is the exception path, and it is now also
        // the only way to put a contract on risk before anyone has paid for it -- so "why" is not
        // optional. reasonForManualIssue is still supplied here: free text is not a substitute
        // for a value a report can group by, and this proves the endpoint agrees.
        mockMvc.perform(post("/policies/manual-issue")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"underwritingCaseId":"%s","policyholderPartyId":"%s","productVersionId":"%s",
                     "sumAssured":{"amount":"1000000.00","currencyCode":"TZS"},
                     "premiumAmount":{"amount":"15000.00","currencyCode":"TZS"},"agentOfRecordId":null,
                     "reasonForManualIssue":"No basis given"}
                    """.formatted(caseId, applicantId, product.productVersionId())))
            // No openApi().isValid(...) here, unlike every other call in this class: the request
            // is deliberately spec-invalid, so the validator would fail it on the way IN and the
            // 400 would never be reached. The status is the assertion. Making the field required
            // also made 400 reachable on this path for the first time, which is why the spec now
            // declares it -- strict validation caught that omission from this very test.
            .andExpect(status().isBadRequest());
    }

    /**
     * The basis is not decoration: its value decides whether cover starts.
     *
     * <p>Each call uses a fresh underwriting case deliberately. One case issues one policy, so
     * reusing it would 409 on the second and this test would pass for the wrong reason.
     */
    @Test
    void aMigrationIsActiveImmediatelyAndAnOverrideWaitsForTheMoney() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID applicantId = registerApplicant(tenantId, "9102");
        ProductFixture product = publishProduct(tenantId, "POLICY-CONTRACT-BASIS-01", "TERM_LIFE");

        mockMvc.perform(post("/policies/manual-issue")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"issuanceBasis":"MIGRATION","underwritingCaseId":"%s","policyholderPartyId":"%s","productVersionId":"%s",
                     "sumAssured":{"amount":"1000000.00","currencyCode":"TZS"},
                     "premiumAmount":{"amount":"15000.00","currencyCode":"TZS"},"agentOfRecordId":null,
                     "reasonForManualIssue":"Brought in from the legacy book"}
                    """.formatted(openUnderwritingCase(tenantId, applicantId, product), applicantId,
                        product.productVersionId())))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(post("/policies/manual-issue")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"issuanceBasis":"UNDERWRITING_OVERRIDE","underwritingCaseId":"%s","policyholderPartyId":"%s","productVersionId":"%s",
                     "sumAssured":{"amount":"1000000.00","currencyCode":"TZS"},
                     "premiumAmount":{"amount":"15000.00","currencyCode":"TZS"},"agentOfRecordId":null,
                     "reasonForManualIssue":"Senior underwriter overturned the automated decline"}
                    """.formatted(openUnderwritingCase(tenantId, applicantId, product), applicantId,
                        product.productVersionId())))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.status").value("PROPOSED"));
    }

    private IssuedPolicy manualIssueOffer(UUID tenantId, String productCode, String category) throws Exception {
        UUID applicantId = registerApplicant(tenantId, String.valueOf(Math.abs(productCode.hashCode() % 10000)));
        ProductFixture product = publishProduct(tenantId, productCode, category);
        UUID caseId = openUnderwritingCase(tenantId, applicantId, product);

        String response = mockMvc.perform(post("/policies/manual-issue")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"issuanceBasis":"UNDERWRITING_OVERRIDE","underwritingCaseId":"%s","policyholderPartyId":"%s","productVersionId":"%s",
                     "sumAssured":{"amount":"1000000.00","currencyCode":"TZS"},
                     "premiumAmount":{"amount":"15000.00","currencyCode":"TZS"},"agentOfRecordId":"%s",
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
                    {"issuanceBasis":"UNDERWRITING_OVERRIDE","underwritingCaseId":"%s","policyholderPartyId":"%s","productVersionId":"%s",
                     "sumAssured":{"amount":"1000000.00","currencyCode":"TZS"},
                     "premiumAmount":{"amount":"15000.00","currencyCode":"TZS"},"agentOfRecordId":null,
                     "reasonForManualIssue":"Should be rejected before reaching the service layer"}
                    """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())))
            .andExpect(status().isForbidden());
    }

    @Test
    void manualIssueReturns404ForANonexistentPolicyholderPartyId() throws Exception {
        // productVersionId MUST be real here (unlike a first draft of this test, which used a
        // random one) -- PolicyController.manualIssue calls productApi.getSnapshotByVersionId
        // as its very first statement, before ever constructing PolicyApi.IssueRequest or
        // calling policyApi.issuePolicy (where the party-existence check under test actually
        // lives, in PolicyApiImpl.issuePolicy). A nonexistent productVersionId would 404 there
        // first, letting this test pass even with the party check deleted entirely -- a false
        // positive. Using a real, published product version (same publishProduct fixture the
        // rest of this file's HTTP-level tests use) forces the request past that lookup so the
        // 404 asserted below is genuinely caused by policyholderPartyId, not productVersionId.
        UUID tenantId = UUID.randomUUID();
        UUID nonexistentPartyId = UUID.randomUUID();
        ProductFixture product = publishProduct(tenantId, "POLICY-CONTRACT-404PARTY", "TERM_LIFE");

        mockMvc.perform(post("/policies/manual-issue")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"issuanceBasis":"UNDERWRITING_OVERRIDE","underwritingCaseId":"%s","policyholderPartyId":"%s","productVersionId":"%s",
                     "sumAssured":{"amount":"1000000.00","currencyCode":"TZS"},
                     "premiumAmount":{"amount":"15000.00","currencyCode":"TZS"},"agentOfRecordId":"%s",
                     "reasonForManualIssue":"Contract test -- nonexistent policyholder"}
                    """.formatted(UUID.randomUUID(), nonexistentPartyId, product.productVersionId(), UUID.randomUUID())))
            .andExpect(status().isNotFound());
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

    // --- GET /beneficiaries?partyId= : the reverse direction --------------------------------

    /**
     * "Which policies pay out to this person" — previously unanswerable, because beneficiary rows
     * were only ever read by policy number. The policyholder is named as their own beneficiary here
     * purely because {@code manualIssue} already gives the test a real party id to use.
     */
    @Test
    void beneficiaryOfReturnsThePoliciesNamingThatParty() throws Exception {
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy issued = manualIssue(tenantId, "POLICY-CONTRACT-BOF");

        mockMvc.perform(put("/policies/" + issued.policyNumber() + "/beneficiaries")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    [{"type":"PARTY","partyId":"%s","sharePercent":100,"revocable":true}]
                    """.formatted(issued.policyholderPartyId())))
            .andExpect(status().isOk());

        mockMvc.perform(get("/beneficiaries")
                .queryParam("partyId", issued.policyholderPartyId().toString())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].policyNumber").value(issued.policyNumber()))
            .andExpect(jsonPath("$[0].sharePercent").value(100))
            // Carried because a share without a status implies a benefit that may not exist.
            .andExpect(jsonPath("$[0].policyStatus").exists());
    }

    @Test
    void beneficiaryOfReturnsEmptyForAPartyNamedOnNothing() throws Exception {
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy issued = manualIssue(tenantId, "POLICY-CONTRACT-BOF2");

        // Issued with no beneficiaries: the policyholder is named on nothing. An empty list, not
        // a 404 -- "this person is a beneficiary of no policies" is an answer, not an absence.
        mockMvc.perform(get("/beneficiaries")
                .queryParam("partyId", issued.policyholderPartyId().toString())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    /**
     * Scoped exactly as the party-detail read is, and for a stronger reason: this reveals who stands
     * to be paid on someone else's contract.
     */
    @Test
    void anAgentCannotAskAboutAPartyItDidNotRegister() throws Exception {
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy issued = manualIssue(tenantId, "POLICY-CONTRACT-BOF3");

        mockMvc.perform(get("/beneficiaries")
                .queryParam("partyId", issued.policyholderPartyId().toString())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.subject("agent-who-registered-nobody")
                        .claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isForbidden());
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
    void inForceMatchesOpenApiContractAndReturnsTrueForAPolicyWhosePremiumHasCleared() throws Exception {
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy issued = manualIssue(tenantId, "POLICY-CONTRACT-12");

        mockMvc.perform(get("/policies/" + issued.policyNumber() + "/in-force")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.inForce").value(true));
    }

    /**
     * Renamed from "...ForANewlyIssuedPolicy" and given this counterpart, because newly issued is
     * exactly what no longer implies in force. Without this pair the endpoint could return true
     * unconditionally and the test above would not notice.
     */
    @Test
    void inForceReturnsFalseForAnOfferWhosePremiumHasNotCleared() throws Exception {
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy issued = manualIssueOffer(tenantId, "POLICY-CONTRACT-12-OFFER", "TERM_LIFE");

        mockMvc.perform(get("/policies/" + issued.policyNumber() + "/in-force")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.inForce").value(false));
    }

    // --- suspend/resume/reinstate: PolicyApi.suspendPolicy/resumeSuspendedPolicy/reinstatePolicy
    // were fully implemented, tested, and event-publishing since M3, but had NO controller mapping
    // at all until this fix -- these are the falsifiable proof that the real gap is closed, not
    // just documented as closed.

    @Test
    void suspendPolicyMatchesOpenApiContractAndTransitionsToSuspended() throws Exception {
        // POLICY_SUSPENSION_ELIGIBLE_CATEGORIES (refdata/V2) seeds only GROUP_LIFE.
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy issued = manualIssue(tenantId, "POLICY-CONTRACT-13", "GROUP_LIFE");

        mockMvc.perform(post("/policies/" + issued.policyNumber() + "/suspend")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"reason":"Employer group scheme in arrears"}
                    """))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.status").value("SUSPENDED"));
    }

    @Test
    void suspendPolicyRejectsAnIneligibleProductCategoryWith409() throws Exception {
        // TERM_LIFE is NOT in POLICY_SUSPENSION_ELIGIBLE_CATEGORIES -- PolicyApiImpl's own
        // eligibility check, not the ACTIVE-only guard, must be what rejects this.
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy issued = manualIssue(tenantId, "POLICY-CONTRACT-14");

        mockMvc.perform(post("/policies/" + issued.policyNumber() + "/suspend")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"reason":"Should be rejected -- TERM_LIFE is not suspension-eligible"}
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errorCode").value("INVALID_POLICY_STATE"));
    }

    @Test
    void suspendPolicyRejectsNonStaffCallerWith403() throws Exception {
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy issued = manualIssue(tenantId, "POLICY-CONTRACT-15", "GROUP_LIFE");

        mockMvc.perform(post("/policies/" + issued.policyNumber() + "/suspend")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"reason":"Should be rejected before reaching the service layer"}
                    """))
            .andExpect(status().isForbidden());
    }

    @Test
    void suspendPolicyRejectsABlankReasonWith400() throws Exception {
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy issued = manualIssue(tenantId, "POLICY-CONTRACT-16", "GROUP_LIFE");

        mockMvc.perform(post("/policies/" + issued.policyNumber() + "/suspend")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"reason":""}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void resumePolicyMatchesOpenApiContractAndTransitionsBackToActive() throws Exception {
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy issued = manualIssue(tenantId, "POLICY-CONTRACT-17", "GROUP_LIFE");
        mockMvc.perform(post("/policies/" + issued.policyNumber() + "/suspend")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"reason":"Employer group scheme in arrears"}
                    """))
            .andExpect(status().isOk());

        mockMvc.perform(post("/policies/" + issued.policyNumber() + "/resume")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void resumePolicyRejectsAnAlreadyActivePolicyWith409() throws Exception {
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy issued = manualIssue(tenantId, "POLICY-CONTRACT-18", "GROUP_LIFE");

        mockMvc.perform(post("/policies/" + issued.policyNumber() + "/resume")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errorCode").value("INVALID_POLICY_STATE"));
    }

    @Test
    void reinstatePolicyMatchesOpenApiContractAndTransitionsLapsedToReinstated() throws Exception {
        // No HTTP path to LAPSED exists (lapsePolicy is only ever called automatically off
        // arrears, per the audit) -- same direct-PolicyApi-call fixture idiom as issueTestPolicy
        // above, used here only to reach the precondition state, not to bypass the assertion.
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy issued = manualIssue(tenantId, "POLICY-CONTRACT-19", "GROUP_LIFE");
        TenantContext.set(tenantId);
        policyApi.lapsePolicy(issued.policyNumber(), "test-fixture");
        TenantContext.clear();

        mockMvc.perform(post("/policies/" + issued.policyNumber() + "/reinstate")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.status").value("REINSTATED"));
    }

    @Test
    void reinstatePolicyRejectsANonLapsedPolicyWith409() throws Exception {
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy issued = manualIssue(tenantId, "POLICY-CONTRACT-20", "GROUP_LIFE");

        mockMvc.perform(post("/policies/" + issued.policyNumber() + "/reinstate")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errorCode").value("INVALID_POLICY_STATE"));
    }

    @Test
    void reinstatePolicyRejectsNonStaffCallerWith403() throws Exception {
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy issued = manualIssue(tenantId, "POLICY-CONTRACT-21", "GROUP_LIFE");

        mockMvc.perform(post("/policies/" + issued.policyNumber() + "/reinstate")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isForbidden());
    }

    // --- Staff list improvements: newest-first default sort, q free-text search ------------------

    @Test
    void searchPoliciesOrdersNewestCreatedFirstByDefault() throws Exception {
        UUID tenantId = UUID.randomUUID();
        // Two real policies issued in sequence -- the second one issued must be
        // items[0], proving a real ORDER BY, not incidentally-already-sorted seed data.
        String firstPolicy = manualIssue(tenantId, "SORT-ORDER-FIRST").policyNumber();
        String secondPolicy = manualIssue(tenantId, "SORT-ORDER-SECOND").policyNumber();

        mockMvc.perform(get("/policies")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].policyNumber").value(secondPolicy))
            .andExpect(jsonPath("$.items[1].policyNumber").value(firstPolicy));
    }

    @Test
    void searchPoliciesByQMatchesACaseInsensitiveSubstringOfPolicyNumber() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String policyNumber = manualIssue(tenantId, "Q-SEARCH-FIXTURE").policyNumber();
        // A second, real, unrelated policy in the SAME tenant -- without it, an
        // IGNORED q param would still return "everything in this fresh tenant"
        // (exactly 1 item), passing for the wrong reason.
        manualIssue(tenantId, "Q-SEARCH-OTHER");

        // Deliberately lowercased query against a real POL-XXXXXXXX (uppercase-hex)
        // policy number -- falsifies "ILIKE is inherently case-insensitive" against a
        // real row rather than trusting the SQL.
        mockMvc.perform(get("/policies")
                .queryParam("q", policyNumber.toLowerCase())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].policyNumber").value(policyNumber));
    }

    @Test
    void searchPoliciesByQCombinesWithStatus() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String policyNumber = manualIssue(tenantId, "Q-COMBO-FIXTURE").policyNumber();
        // A second real, ACTIVE policy in the same tenant -- status=ACTIVE ALONE
        // would match both, so only a genuinely-applied q narrows this to one.
        manualIssue(tenantId, "Q-COMBO-OTHER");

        mockMvc.perform(get("/policies")
                .queryParam("q", policyNumber)
                .queryParam("status", "ACTIVE")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].policyNumber").value(policyNumber));
    }

    @Test
    void searchPoliciesByQReturnsEmptyForNoMatches() throws Exception {
        UUID tenantId = UUID.randomUUID();
        mockMvc.perform(get("/policies")
                .queryParam("q", "NoPolicyAnywhereHasThisExactNonsenseNumber12345")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(0));
    }

    // --- Group business ------------------------------------------------------------------
    //
    // The whole scheme surface goes over real HTTP and through the OpenAPI validator, because
    // the wire shape is where a group scheme is easiest to get quietly wrong: `fcl` is nullable
    // and means something different from zero, and every member money field is nullable for a
    // member not yet in force. A schema that disagrees with the controller on any of those
    // produces a page that renders "0" where it should say "no limit".

    private UUID staffRegisteredPerson(UUID tenantId, String phoneSuffix) throws Exception {
        return registerApplicant(tenantId, phoneSuffix);
    }

    /**
     * Putting a scheme on risk is an underwriting act, and a plain staff token is not enough.
     *
     * <p>These four endpoints shipped at bare {@code hasRole('REALM_STAFF')} while every other
     * decision to take risk on this platform was role-gated — an underwriting assessment and
     * decision to UNDERWRITER, a claim assessment to CLAIMS_ASSESSOR, a settlement to
     * CLAIMS_MANAGER, an invoice waiver to FINANCE_OFFICER. A claims assessor could put a
     * 500-life scheme on the books.
     *
     * <p>Both halves are asserted, because the gate is only correct if it is narrow: writes
     * refuse a staff token without the role, and READS still accept one. A claims assessor
     * must be able to check whether a life was covered when a death is reported — that is what
     * {@code idx_policy_member_party} exists for.
     */
    @Test
    void onlyAnUnderwriterMayPutASchemeOnRiskButAnyStaffMayReadOne() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID employer = staffRegisteredPerson(tenantId, "8101");
        UUID member = staffRegisteredPerson(tenantId, "8102");
        ProductFixture product = publishProduct(tenantId, "GRP-ROLE-01", "GROUP_LIFE");
        String body = """
            {"policyholderPartyId":"%s","productVersionId":"%s","agentOfRecordId":null,
             "benefitBasis":"FLAT","flatBenefitAmount":"5000000.00","currency":"TZS",
             "openingSchedule":[{"memberPartyId":"%s"}],
             "premium":{"amount":"1200000.00","currencyCode":"TZS"},
             "premiumFrequency":"ANNUALLY","reasonForManualIssue":"Role gate test"}
            """.formatted(employer, product.productVersionId(), member);

        // A plain staff token cannot open a scheme.
        mockMvc.perform(post("/group-schemes")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden());

        // An underwriter can.
        String response = mockMvc.perform(post("/group-schemes")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"),
                        new SimpleGrantedAuthority("ROLE_UNDERWRITER"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String policyNumber = JsonPath.read(response, "$.policyNumber");

        // Nor may plain staff admit a life to one.
        mockMvc.perform(post("/group-schemes/" + policyNumber + "/members")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"memberPartyId\":\"%s\"}".formatted(staffRegisteredPerson(tenantId, "8103"))))
            .andExpect(status().isForbidden());

        // But reading the scheme and its schedule stays open to any staff token.
        mockMvc.perform(get("/group-schemes/" + policyNumber)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk());
        mockMvc.perform(get("/group-schemes/" + policyNumber + "/members")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk());
    }

    @Test
    void issuingAGroupSchemeOverHttpMatchesTheSpecAndDerivesItsTotal() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID employer = staffRegisteredPerson(tenantId, "8001");
        UUID memberOne = staffRegisteredPerson(tenantId, "8002");
        UUID memberTwo = staffRegisteredPerson(tenantId, "8003");
        ProductFixture product = publishProduct(tenantId, "GRP-CONTRACT-01", "GROUP_LIFE");

        String response = mockMvc.perform(post("/group-schemes")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_UNDERWRITER"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"policyholderPartyId":"%s","productVersionId":"%s","agentOfRecordId":null,
                     "benefitBasis":"FLAT","flatBenefitAmount":"5000000.00","currency":"TZS",
                     "openingSchedule":[{"memberPartyId":"%s"},{"memberPartyId":"%s"}],
                     "premium":{"amount":"1200000.00","currencyCode":"TZS"},
                     "premiumFrequency":"ANNUALLY","reasonForManualIssue":"Contract test scheme"}
                    """.formatted(employer, product.productVersionId(), memberOne, memberTwo)))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            // Derived from the two-life schedule, not supplied by the caller.
            .andExpect(jsonPath("$.totalCovered.amount").value("10000000.00"))
            .andExpect(jsonPath("$.activeMemberCount").value(2))
            .andExpect(jsonPath("$.membersRequiringEvidence").value(0))
            // A scheme with no free cover limit sends null, never zero: the two mean
            // opposite things, and a screen that reads a zero here would show "everyone
            // needs underwriting" for a scheme where nobody does.
            .andExpect(jsonPath("$.fcl").doesNotExist())
            .andReturn().getResponse().getContentAsString();

        String policyNumber = JsonPath.read(response, "$.policyNumber");

        mockMvc.perform(get("/group-schemes/" + policyNumber)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.totalCovered.amount").value("10000000.00"));

        // The master policy reads back as a policy too, carrying the same total.
        mockMvc.perform(get("/policies/" + policyNumber)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.sumAssured.amount").value("10000000.00"))
            // V8: the lives are the schedule, so the master names none.
            .andExpect(jsonPath("$.lifeAssuredPartyId").doesNotExist());
    }

    @Test
    void theMemberScheduleAndAJoinerMatchTheSpec() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID employer = staffRegisteredPerson(tenantId, "8101");
        UUID founding = staffRegisteredPerson(tenantId, "8102");
        UUID joiner = staffRegisteredPerson(tenantId, "8103");
        ProductFixture product = publishProduct(tenantId, "GRP-CONTRACT-02", "GROUP_LIFE");

        // 3x salary against a 30,000,000 free cover limit: the founding member on
        // 20,000,000 is worth 60,000,000 and is therefore over it.
        String scheme = mockMvc.perform(post("/group-schemes")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_UNDERWRITER"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"policyholderPartyId":"%s","productVersionId":"%s","agentOfRecordId":null,
                     "benefitBasis":"SALARY_MULTIPLE","salaryMultiple":3,"fclAmount":"30000000.00",
                     "currency":"TZS",
                     "openingSchedule":[{"memberPartyId":"%s","salaryAmount":"20000000.00"}],
                     "premium":{"amount":"900000.00","currencyCode":"TZS"},"premiumFrequency":"ANNUALLY"}
                    """.formatted(employer, product.productVersionId(), founding)))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.membersRequiringEvidence").value(1))
            // Covered up to the limit, not for the full benefit, and not for nothing.
            .andExpect(jsonPath("$.totalCovered.amount").value("30000000.00"))
            .andExpect(jsonPath("$.fcl.amount").value("30000000.00"))
            .andReturn().getResponse().getContentAsString();
        String policyNumber = JsonPath.read(scheme, "$.policyNumber");

        mockMvc.perform(get("/group-schemes/" + policyNumber + "/members")
                .queryParam("status", "ACTIVE")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].underwritingStatus").value("EVIDENCE_REQUIRED"))
            // Both figures are carried, and they differ. The gap IS the outstanding
            // underwriting; sending only one of them would hide it.
            .andExpect(jsonPath("$.items[0].benefit.amount").value("60000000.00"))
            .andExpect(jsonPath("$.items[0].covered.amount").value("30000000.00"))
            .andExpect(jsonPath("$.page.totalElements").value(1));

        mockMvc.perform(post("/group-schemes/" + policyNumber + "/members")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_UNDERWRITER"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"memberPartyId":"%s","salaryAmount":"5000000.00"}
                    """.formatted(joiner)))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.underwritingStatus").value("WITHIN_FCL"))
            .andExpect(jsonPath("$.covered.amount").value("15000000.00"));

        // 30,000,000 (capped) + 15,000,000 (in full), restated on the contract itself.
        mockMvc.perform(get("/policies/" + policyNumber)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sumAssured.amount").value("45000000.00"));
    }

    /** A person with a KNOWN name, for the member-search tests -- the shared helper above
     *  registers everyone as "Policy Contract Applicant", which cannot be searched for. */
    private UUID namedPerson(UUID tenantId, String fullName, String phoneSuffix) throws Exception {
        String response = mockMvc.perform(post("/parties/individuals")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"" + fullName
                    + "\",\"dateOfBirth\":\"1988-03-15\",\"contactInfo\":{\"phoneNumber\":\"+25571234"
                    + phoneSuffix + "\"}}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.partyId"));
    }

    /** Issues a two-life scheme whose members have distinct, searchable names. */
    private String schemeWithTwoNamedMembers(UUID tenantId, String productCode, String suffixBase,
                                                UUID first, UUID second) throws Exception {
        // Four digits: the phone is +255 plus NINE, and a short suffix is a 400 on
        // registration rather than anything to do with schemes.
        UUID employer = namedPerson(tenantId, "Search Employer " + suffixBase, suffixBase + "00");
        ProductFixture product = publishProduct(tenantId, productCode, "GROUP_LIFE");
        String scheme = mockMvc.perform(post("/group-schemes")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_UNDERWRITER"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"policyholderPartyId":"%s","productVersionId":"%s","agentOfRecordId":null,
                     "benefitBasis":"FLAT","flatBenefitAmount":"5000000.00","currency":"TZS",
                     "openingSchedule":[{"memberPartyId":"%s"},{"memberPartyId":"%s"}],
                     "premium":{"amount":"600000.00","currencyCode":"TZS"},"premiumFrequency":"ANNUALLY"}
                    """.formatted(employer, product.productVersionId(), first, second)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return JsonPath.read(scheme, "$.policyNumber");
    }

    /**
     * Searching a member roll by name.
     *
     * <p>A 500-life schedule cannot be read by eye, so this is the only way to answer "is
     * this person covered" without paging the whole roll. The interesting part is WHERE the
     * name comes from: a member row holds a party id and nothing else, so the party module
     * resolves names to ids and the roll is filtered on those.
     *
     * <p>Every assertion below is falsifiable. A filter that was silently dropped would
     * still return the member the caller wanted, so each case pins the row that must be
     * ABSENT and the total that must have shrunk.
     */
    @Test
    void aMemberRollCanBeSearchedByName() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID zawadi = namedPerson(tenantId, "Zawadi Roll Fixture", "8201");
        UUID mwangaza = namedPerson(tenantId, "Mwangaza Roll Fixture", "8202");
        String policyNumber = schemeWithTwoNamedMembers(tenantId, "GRP-SEARCH-01", "82", zawadi, mwangaza);

        // Both lives, unsearched -- the baseline the filtered results must differ from.
        mockMvc.perform(get("/group-schemes/" + policyNumber + "/members")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page.totalElements").value(2));

        // Deliberately the wrong case: a case-sensitive match would find nobody here.
        mockMvc.perform(get("/group-schemes/" + policyNumber + "/members")
                .queryParam("q", "zawadi")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].memberPartyId").value(zawadi.toString()))
            // The total is the SEARCH's total, not the roll's. A pager reading "1-1 of 2"
            // under one row would send the reader looking for a second page that is empty.
            .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    /**
     * The empty-result path, which is a syntax error waiting to happen rather than a
     * cosmetic case: no party matches, so the id set is empty, and an empty set handed to a
     * SQL {@code IN} is a Postgres error while a null one would mean "no filter" and return
     * the whole schedule for a search that matched nobody.
     */
    @Test
    void aMemberSearchMatchingNobodyIsAnEmptyPageNotAnErrorAndNotTheWholeRoll() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID first = namedPerson(tenantId, "Present Roll Fixture", "8301");
        UUID second = namedPerson(tenantId, "Also Present Fixture", "8302");
        String policyNumber = schemeWithTwoNamedMembers(tenantId, "GRP-SEARCH-02", "83", first, second);

        mockMvc.perform(get("/group-schemes/" + policyNumber + "/members")
                .queryParam("q", "NobodyOnThisSchemeIsCalledThis12345")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(0))
            .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    /**
     * The search is scoped to THIS scheme, and combines with status rather than replacing it.
     *
     * <p>The scoping half matters most: the name is resolved across the whole tenant, so a
     * person of that name who is on a DIFFERENT scheme must not appear on this one's roll.
     * Getting that wrong would put someone else's employee on an employer's schedule.
     */
    @Test
    void aMemberSearchStaysOnItsOwnSchemeAndComposesWithStatus() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID onThisScheme = namedPerson(tenantId, "Insider Scope Fixture", "8401");
        UUID alsoOnThisScheme = namedPerson(tenantId, "Second Insider Fixture", "8402");
        UUID onAnotherScheme = namedPerson(tenantId, "Outsider Scope Fixture", "8403");
        UUID otherSchemeCompanion = namedPerson(tenantId, "Outsider Companion Fixture", "8404");

        String thisScheme = schemeWithTwoNamedMembers(tenantId, "GRP-SEARCH-03", "84",
            onThisScheme, alsoOnThisScheme);
        schemeWithTwoNamedMembers(tenantId, "GRP-SEARCH-04", "85",
            onAnotherScheme, otherSchemeCompanion);

        // The outsider exists, is a member of a scheme, and matches the term -- and must
        // still be absent from this roll.
        mockMvc.perform(get("/group-schemes/" + thisScheme + "/members")
                .queryParam("q", "Outsider Scope")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page.totalElements").value(0));

        // ANDed with status, not either-or: an ACTIVE search for a name on the roll finds
        // it, and the same name with the other status finds nothing.
        mockMvc.perform(get("/group-schemes/" + thisScheme + "/members")
                .queryParam("q", "Insider Scope")
                .queryParam("status", "ACTIVE")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].memberPartyId").value(onThisScheme.toString()));

        mockMvc.perform(get("/group-schemes/" + thisScheme + "/members")
                .queryParam("q", "Insider Scope")
                .queryParam("status", "EXITED")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    /**
     * An individual policy read as a scheme answers 409, not 404.
     *
     * <p>"There is no such policy" and "this one is an individual policy" send whoever
     * asked to different places, and a console that got 404 for the second would show a
     * dead end where it should show the policy.
     */
    @Test
    void anIndividualPolicyReadAsASchemeIsAConflictNotANotFound() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String individual = manualIssue(tenantId, "GRP-CONTRACT-NOT-A-SCHEME").policyNumber();

        mockMvc.perform(get("/group-schemes/" + individual)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isConflict())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("INVALID_POLICY_STATE"));
    }

    @Test
    void aSchemeWithAnEmptyScheduleIsRejectedBeforeItReachesTheService() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID employer = staffRegisteredPerson(tenantId, "8201");
        ProductFixture product = publishProduct(tenantId, "GRP-CONTRACT-03", "GROUP_LIFE");

        // @NotEmpty on the DTO, so this is a 422 from Bean Validation rather than the
        // service's own 409 -- both refuse it, and the earlier one gives a field name.
        mockMvc.perform(post("/group-schemes")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_UNDERWRITER"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"policyholderPartyId":"%s","productVersionId":"%s","agentOfRecordId":null,
                     "benefitBasis":"FLAT","flatBenefitAmount":"1000000.00","currency":"TZS",
                     "openingSchedule":[],
                     "premium":{"amount":"100000.00","currencyCode":"TZS"}}
                    """.formatted(employer, product.productVersionId())))
            .andExpect(status().is4xxClientError());
    }
    // --- GET /policies?relatedPartyId (the claims desk's question) --------------------------
    //
    // Three legs, because a claimant is connected to a policy in one of three ways and only
    // one of them is "policyholder". The falsifiable half is in each test: a SECOND policy,
    // belonging to somebody else, exists in the same tenant and must be ABSENT. Without that,
    // every assertion here would pass against a filter that was silently ignored -- the exact
    // vacuous shape this repo has been bitten by before.

    /** A party who owns the contract outright. The leg that already worked, kept as the control. */
    @Test
    void relatedPartyIdFindsAPolicyThePartyOwns() throws Exception {
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy mine = manualIssue(tenantId, "RELATED-OWNER-01");
        IssuedPolicy someoneElses = manualIssue(tenantId, "RELATED-OWNER-02");

        mockMvc.perform(get("/policies")
                .queryParam("relatedPartyId", mine.policyholderPartyId().toString())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.items[?(@.policyNumber == '" + mine.policyNumber() + "')]").exists())
            // The whole point: the filter filters.
            .andExpect(jsonPath("$.items[?(@.policyNumber == '" + someoneElses.policyNumber() + "')]").doesNotExist());
    }

    /**
     * A party insured under a contract somebody else owns -- a parent insuring a child, an
     * employer insuring a key person. `policyholderPartyId` cannot find this policy at all,
     * and a disability or critical-illness claimant is usually exactly this party.
     */
    @Test
    void relatedPartyIdFindsAPolicyWhereThePartyIsOnlyTheLifeAssured() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID lifeAssured = staffRegisteredPerson(tenantId, "7731");
        IssuedPolicy owned = manualIssueForLifeAssured(tenantId, "RELATED-LIFE-01", lifeAssured);
        IssuedPolicy unrelated = manualIssue(tenantId, "RELATED-LIFE-02");

        mockMvc.perform(get("/policies")
                .queryParam("relatedPartyId", lifeAssured.toString())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[?(@.policyNumber == '" + owned.policyNumber() + "')]").exists())
            .andExpect(jsonPath("$.items[?(@.policyNumber == '" + unrelated.policyNumber() + "')]").doesNotExist());

        // Proof the two filters are genuinely different questions and not aliases: the same
        // party as policyholderPartyId finds nothing, because they do not own this contract.
        mockMvc.perform(get("/policies")
                .queryParam("policyholderPartyId", lifeAssured.toString())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[?(@.policyNumber == '" + owned.policyNumber() + "')]").doesNotExist());
    }

    /**
     * The death-claim case, and the reason this parameter exists. The claimant owns nothing and
     * is not the insured life -- they are named on the policy as a beneficiary, and the life
     * assured is the person who died.
     */
    @Test
    void relatedPartyIdFindsAPolicyWhereThePartyIsOnlyABeneficiary() throws Exception {
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy policy = manualIssue(tenantId, "RELATED-BENEF-01");
        IssuedPolicy unrelated = manualIssue(tenantId, "RELATED-BENEF-02");
        UUID beneficiary = staffRegisteredPerson(tenantId, "7732");

        mockMvc.perform(put("/policies/" + policy.policyNumber() + "/beneficiaries")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    [{"type":"PARTY","partyId":"%s","sharePercent":"100.00","revocable":true}]
                    """.formatted(beneficiary)))
            .andExpect(status().isOk());

        mockMvc.perform(get("/policies")
                .queryParam("relatedPartyId", beneficiary.toString())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[?(@.policyNumber == '" + policy.policyNumber() + "')]").exists())
            .andExpect(jsonPath("$.items[?(@.policyNumber == '" + unrelated.policyNumber() + "')]").doesNotExist());
    }

    /**
     * A beneficiary who has since been replaced is not a person to offer a claim form to, and
     * `PolicyApiImpl.toView` already stops returning them. The filter has to agree, or the
     * console would offer a policy whose own beneficiary list no longer names the claimant.
     */
    @Test
    void relatedPartyIdIgnoresABeneficiaryWhoHasBeenReplaced() throws Exception {
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy policy = manualIssue(tenantId, "RELATED-BENEF-03");
        UUID first = staffRegisteredPerson(tenantId, "7733");
        UUID second = staffRegisteredPerson(tenantId, "7734");

        replaceSoleBeneficiary(tenantId, policy.policyNumber(), first);
        // PUT REPLACES the whole set, so this deactivates `first` rather than adding to it.
        replaceSoleBeneficiary(tenantId, policy.policyNumber(), second);

        mockMvc.perform(get("/policies")
                .queryParam("relatedPartyId", second.toString())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[?(@.policyNumber == '" + policy.policyNumber() + "')]").exists());

        mockMvc.perform(get("/policies")
                .queryParam("relatedPartyId", first.toString())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[?(@.policyNumber == '" + policy.policyNumber() + "')]").doesNotExist());
    }

    /**
     * A customer's own `relatedPartyId` is DROPPED rather than honoured, so it cannot be used as
     * an oracle ("does my policy name party Y?") one guessed uuid at a time. Dropping it means
     * the customer still sees their own policies in full -- the parameter simply does nothing --
     * which is why this asserts the policy is STILL THERE rather than asserting a 400.
     */
    @Test
    void aCustomerCannotUseRelatedPartyIdAsAnOracleAgainstTheirOwnPolicy() throws Exception {
        UUID tenantId = UUID.randomUUID();
        IssuedPolicy mine = manualIssue(tenantId, "RELATED-CUST-01");
        UUID strangerNamedOnNothing = staffRegisteredPerson(tenantId, "7735");

        mockMvc.perform(get("/policies")
                .queryParam("relatedPartyId", strangerNamedOnNothing.toString())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())
                        .claim("party_id", mine.policyholderPartyId().toString()))))
            .andExpect(status().isOk())
            // Had the parameter been honoured, this would be absent and the empty result would
            // itself be the answer to "is this stranger connected to my policy" -- no.
            .andExpect(jsonPath("$.items[?(@.policyNumber == '" + mine.policyNumber() + "')]").exists());
    }

    private void replaceSoleBeneficiary(UUID tenantId, String policyNumber, UUID partyId) throws Exception {
        mockMvc.perform(put("/policies/" + policyNumber + "/beneficiaries")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    [{"type":"PARTY","partyId":"%s","sharePercent":"100.00","revocable":true}]
                    """.formatted(partyId)))
            .andExpect(status().isOk());
    }

    private IssuedPolicy manualIssueForLifeAssured(UUID tenantId, String productCode, UUID lifeAssuredPartyId)
            throws Exception {
        UUID applicantId = registerApplicant(tenantId, String.valueOf(Math.abs(productCode.hashCode() % 10000)));
        ProductFixture product = publishProduct(tenantId, productCode, "TERM_LIFE");
        UUID caseId = openUnderwritingCase(tenantId, applicantId, product);

        String response = mockMvc.perform(post("/policies/manual-issue")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"issuanceBasis":"UNDERWRITING_OVERRIDE","underwritingCaseId":"%s","policyholderPartyId":"%s","productVersionId":"%s",
                     "sumAssured":{"amount":"1000000.00","currencyCode":"TZS"},
                     "premiumAmount":{"amount":"15000.00","currencyCode":"TZS"},"agentOfRecordId":"%s",
                     "reasonForManualIssue":"Contract test manual issuance",
                     "lifeAssuredPartyId":"%s"}
                    """.formatted(caseId, applicantId, product.productVersionId(), UUID.randomUUID(),
                        lifeAssuredPartyId)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return new IssuedPolicy(JsonPath.read(response, "$.policyNumber"), applicantId);
    }
}
