package tz.co.nlolo.lifeplatform.claims;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.SpecTypeConformance;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.claims.api.ClaimType;
import tz.co.nlolo.lifeplatform.claims.api.ClaimView;
import tz.co.nlolo.lifeplatform.claims.api.ClaimsApi;
import tz.co.nlolo.lifeplatform.claims.api.DeathClaimDetails;
import tz.co.nlolo.lifeplatform.claims.api.MaturityClaimDetails;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.product.api.*;
import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import com.jayway.jsonpath.JsonPath;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static tz.co.nlolo.lifeplatform.ProductFilingFixture.ANY_FILING;

/**
 * Task 10: HTTP-level contract coverage for {@code ClaimController}/{@code ClaimEvidenceController}
 * against {@code api/openapi/openapi-claims.yaml} -- the falsifiability gate for the whole claims
 * REST surface built across Tasks 4-9. Follows {@code PaymentContractTest}'s structure exactly:
 * {@code @Testcontainers} + {@code @AutoConfigureMockMvc} + {@code @SpringBootTest} +
 * {@code SecurityMockMvcRequestPostProcessors.jwt()} + {@code OpenApiValidationMatchers.openApi()
 * .isValid(SPEC_PATH)}.
 *
 * <p>Fixtures are seeded two ways, mirroring existing precedent rather than reinventing one:
 * policy/product/party setup goes through {@code PartyApi}/{@code ProductApi}/{@code PolicyApi}
 * directly (copied verbatim from {@code ClaimsApiIntegrationTest.buildFixture}/
 * {@code issuePolicyWithNullUnderwritingCase}), and claim fixtures needed only as SETUP for an
 * endpoint under test (not the endpoint itself) go through {@code ClaimsApi} directly too (same
 * idiom {@code PaymentContractTest} uses for its own fixtures) -- only the operation actually being
 * asserted on goes through real HTTP. Every 403 test seeds a REAL, valid claim first, so a broken
 * {@code @PreAuthorize}/ownership check would return 200/201/202, not an incidental 404.
 *
 * <p><b>Two verified, load-bearing facts about the current contract, not assumptions:</b>
 * <ul>
 *   <li>{@code ClaimDetails} in the current {@code openapi-claims.yaml} is a bare {@code oneOf}
 *       with NO {@code discriminator} keyword (confirmed by reading the file directly) -- an
 *       earlier discriminator+oneOf shape failed swagger-request-validator's own internal syntax
 *       check on every schema referencing it, which would have blocked every test below touching
 *       a request or response body that carries {@code ClaimDetails}. That failure mode is not
 *       reproduced by any test here, confirming the fix holds.</li>
 *   <li>{@code POST /claims} declares NO {@code 404} response in the spec (only 201/400/401/403/
 *       422), even though an unknown {@code policyNumber} genuinely reaches
 *       {@code PolicyNotFoundException} -> 404 at runtime. This is a real, pre-existing OpenAPI
 *       contract gap (the spec under-declares a reachable status), not a runtime bug -- see
 *       {@code registerClaimReturns404ForAnUnknownPolicy} below, which omits the
 *       {@code openApi().isValid(SPEC_PATH)} matcher for exactly this reason and asserts status/
 *       errorCode only, the same way a schema-invalid REQUEST body would have to.</li>
 * </ul>
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ClaimsContractTest {

    private static final String SPEC_PATH = "api/openapi/openapi-claims.yaml";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Container
    static final MinIOContainer MINIO = new MinIOContainer("minio/minio:latest");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("minio.endpoint", MINIO::getS3URL);
        registry.add("minio.access-key", MINIO::getUserName);
        registry.add("minio.secret-key", MINIO::getPassword);
    }

    @BeforeAll
    static void applyMigrationsAndCreateBuckets() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V2__seed_policy_loan_parameters.sql",
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
            "db-migrations/document/V1__create_document_schema.sql",
            "db-migrations/document/V2__add_content_type_and_file_name.sql",
            "db-migrations/claims/V1__create_claims_schema.sql",
            "db-migrations/claims/V2__grants_rls_money_checks_evidence_and_settlement_columns.sql",
            "db-migrations/claims/V3__registration_idempotency_key.sql",
            "db-migrations/claims/V5__claim_policy_member.sql",
            "db-migrations/claims/V6__exclusion_decline.sql",
            "db-migrations/claims/V7__claim_assessment_assessor_name.sql",
            "db-migrations/claims/V8__claim_evidence_uploaded_by_name.sql");

        // Only "claim-evidence" is needed here (MinioDocumentStorage.bucketFor routes
        // DocumentType.CLAIM_EVIDENCE there) -- unlike ClaimEvidenceIntegrationTest, this class
        // never uploads a KYC/underwriting/general document, so the other three buckets that
        // infra/docker-compose.yml's minio-init job would otherwise create are omitted.
        MinioClient minioClient = MinioClient.builder()
            .endpoint(MINIO.getS3URL())
            .credentials(MINIO.getUserName(), MINIO.getPassword())
            .build();
        minioClient.makeBucket(MakeBucketArgs.builder().bucket("claim-evidence").build());
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private PolicyApi policyApi;
    @Autowired private ClaimsApi claimsApi;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    // --- Fixture plumbing, copied verbatim from ClaimsApiIntegrationTest ----------------------

    private record Fixture(UUID applicantId, UUID productId, UUID productVersionId) {}

    private Fixture buildFixture(UUID tenantId, String productCode) {
        TenantContext.set(tenantId);
        PartyView applicant = partyApi.registerIndividual("Claims Contract Test Applicant " + productCode,
            LocalDate.of(1985, 3, 1), "+25571600" + String.format("%04d", Math.abs(productCode.hashCode() % 10000)),
            null, "test-agent");
        ProductSummaryView product = productApi.createProduct(productCode, "Claims Contract Test Product",
            ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
                        // Every benefit this class registers a claim against. All at SUM_ASSURED, so each
            // claim is valued at the policy sum assured exactly as it was before benefits drove
            // coverage -- no existing amount assertion moves. Before this, a MATURITY or
            // DISABILITY claim was valued at the death benefit because claimableCover took no
            // claim type at all.
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED),
                    new ProductApi.BenefitInput(BenefitType.DISABILITY, BenefitCalculationMethod.SUM_ASSURED),
                    new ProductApi.BenefitInput(BenefitType.CRITICAL_ILLNESS, BenefitCalculationMethod.SUM_ASSURED),
                    new ProductApi.BenefitInput(BenefitType.MATURITY, BenefitCalculationMethod.SUM_ASSURED)),
            null, ANY_FILING, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        TenantContext.clear();
        return new Fixture(applicant.partyId(), product.productId(), snapshot.productVersionId());
    }

    private String issuePolicy(UUID tenantId, Fixture fixture) {
        TenantContext.set(tenantId);
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(fixture.applicantId(), fixture.productId(),
            fixture.productVersionId(), new BigDecimal("2000000"), "TZS", new BigDecimal("40000.00"), "TZS",
            "MONTHLY", null, List.of(), "Claims contract test");
        String policyNumber = policyApi.issuePolicy(null, request, "test-staff").policyNumber();
        // Cover starts with the first premium. This fixture needs a policy on risk.
        policyApi.activateOnFirstPremium(policyNumber);
        TenantContext.clear();
        return policyNumber;
    }

    /** Registers a real DEATH claim directly through {@link ClaimsApi} (bypassing HTTP) -- the
     * fixture technique for every test below whose target endpoint is NOT {@code POST /claims}
     * itself. Returns the claim's id with {@link TenantContext} cleared afterwards. */
    private UUID registerDeathClaim(UUID tenantId, UUID claimantId, String policyNumber) {
        TenantContext.set(tenantId);
        LocalDate dateOfEvent = LocalDate.now().minusDays(1);
        ClaimView view = claimsApi.registerClaim(
            new ClaimsApi.RegisterClaimRequest(policyNumber, null, claimantId, ClaimType.DEATH, dateOfEvent,
                new DeathClaimDetails("Natural causes", "Dar es Salaam", dateOfEvent, "Dr. Test")),
            "ct-reg-" + UUID.randomUUID(), "claims-staff-fixture");
        TenantContext.clear();
        return view.claimId();
    }

    /** MATURITY needs no assessment to reach SETTLEMENT_REQUESTED/SETTLED, but here it is used
     * simply as a REGISTERED claim for the evidence tests, which need no particular claim type. */
    private UUID registerMaturityClaim(UUID tenantId, UUID claimantId, String policyNumber) {
        TenantContext.set(tenantId);
        LocalDate dateOfEvent = LocalDate.now().minusDays(1);
        ClaimView view = claimsApi.registerClaim(
            new ClaimsApi.RegisterClaimRequest(policyNumber, null, claimantId, ClaimType.MATURITY, dateOfEvent,
                new MaturityClaimDetails(dateOfEvent)),
            "ct-reg-" + UUID.randomUUID(), "claims-staff-fixture");
        TenantContext.clear();
        return view.claimId();
    }

    private void submitAssessmentDirectly(UUID tenantId, UUID claimId, String assessor) {
        TenantContext.set(tenantId);
        claimsApi.submitAssessment(claimId, "Fixture findings", new BigDecimal("2000000"), "TZS", false, assessor, null);
        TenantContext.clear();
    }

    private void decideSettlementDirectly(UUID tenantId, UUID claimId, boolean approved, String decidedBy) {
        TenantContext.set(tenantId);
        if (approved) {
            claimsApi.decideSettlement(claimId, true, new BigDecimal("2000000"), "TZS", null,
                "payee-ref-fixture", "ct-settle-" + UUID.randomUUID(), decidedBy);
        } else {
            claimsApi.decideSettlement(claimId, false, null, null, "Fixture rejection reason", null, null, decidedBy);
        }
        TenantContext.clear();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor staffOf(UUID tenantId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
            .jwt(builder -> builder.claim("tenant_id", tenantId.toString()));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor customerOf(UUID tenantId, UUID partyId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
            .jwt(builder -> builder.claim("tenant_id", tenantId.toString()).claim("party_id", partyId.toString()));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor assessorOf(UUID tenantId, String subject) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_CLAIMS_ASSESSOR"), new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
            .jwt(builder -> builder.subject(subject).claim("tenant_id", tenantId.toString()));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor managerOf(UUID tenantId, String subject) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_CLAIMS_MANAGER"), new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
            .jwt(builder -> builder.subject(subject).claim("tenant_id", tenantId.toString()));
    }

    // ============================================================================================
    // POST /claims
    // ============================================================================================

    @Test
    void registerClaimReturns201ForAValidDeathClaim() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-CT-REG-01");
        String policyNumber = issuePolicy(tenantId, fixture);
        LocalDate dateOfEvent = LocalDate.now().minusDays(2);

        mockMvc.perform(post("/claims")
                .with(staffOf(tenantId))
                .header("Idempotency-Key", "ct-http-reg-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"policyNumber":"%s","claimantPartyId":"%s","claimType":"DEATH","dateOfEvent":"%s",
                     "details":{"claimType":"DEATH","causeOfDeath":"Cardiac arrest","placeOfDeath":"Dar es Salaam",
                     "dateOfDeath":"%s","attendingPhysician":"Dr. Kessy"}}
                    """.formatted(policyNumber, fixture.applicantId(), dateOfEvent, dateOfEvent)))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.policyNumber").value(policyNumber))
            .andExpect(jsonPath("$.claimantPartyId").value(fixture.applicantId().toString()))
            .andExpect(jsonPath("$.claimType").value("DEATH"))
            .andExpect(jsonPath("$.status").value("REGISTERED"))
            .andExpect(jsonPath("$.details.causeOfDeath").value("Cardiac arrest"));
    }

    @Test
    void registerClaimReturns422WhenPolicyNotInForceOnDateOfEvent() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-CT-LAPSE-01");
        String policyNumber = issuePolicy(tenantId, fixture);
        TenantContext.set(tenantId);
        policyApi.lapsePolicy(policyNumber, "test-staff"); // isPolicyInForce now returns false
        TenantContext.clear();

        mockMvc.perform(post("/claims")
                .with(staffOf(tenantId))
                .header("Idempotency-Key", "ct-http-lapse-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"policyNumber":"%s","claimantPartyId":"%s","claimType":"DEATH","dateOfEvent":"%s",
                     "details":{"claimType":"DEATH","causeOfDeath":"Cardiac arrest","placeOfDeath":"Dar es Salaam",
                     "dateOfDeath":"%s","attendingPhysician":"Dr. Kessy"}}
                    """.formatted(policyNumber, fixture.applicantId(), LocalDate.now(), LocalDate.now())))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("CLAIM_VALIDATION_FAILED"));
    }

    @Test
    void registerClaimReturns422WhenDetailsClaimTypeDisagreesWithDeclaredType() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-CT-MISMATCH-01");
        String policyNumber = issuePolicy(tenantId, fixture);
        LocalDate dateOfEvent = LocalDate.now().minusDays(1);

        // Top-level claimType says DEATH, details.claimType says DISABILITY -- Claim's own
        // constructor (Claim.java:120-124) must reject this with a 422, not a 400/500.
        mockMvc.perform(post("/claims")
                .with(staffOf(tenantId))
                .header("Idempotency-Key", "ct-http-mismatch-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"policyNumber":"%s","claimantPartyId":"%s","claimType":"DEATH","dateOfEvent":"%s",
                     "details":{"claimType":"DISABILITY","disabilityType":"Loss of limb","onsetDate":"%s",
                     "permanent":true,"impairmentPercent":"50"}}
                    """.formatted(policyNumber, fixture.applicantId(), dateOfEvent, dateOfEvent)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("CLAIM_VALIDATION_FAILED"));
    }

    /** See this class's javadoc: {@code POST /claims} declares no {@code 404} response in
     * openapi-claims.yaml at all, even though {@code PolicyNotFoundException} genuinely produces
     * one at runtime for an unknown policyNumber -- a real spec-completeness gap, verified by the
     * fact that including {@code openApi().isValid(SPEC_PATH)} here fails with "No response is
     * defined for status 404" against an otherwise entirely correct response. Asserts status/
     * errorCode only, exactly the workaround the schema-invalid-REQUEST gotcha elsewhere in this
     * file already documents, just triggered from the response side instead of the request side.
     * The claimant party is real (registered via buildFixture) so this genuinely isolates "unknown
     * policy", not "unknown party" -- a PartyNotFoundException would look identical at this
     * assertion's granularity if the claimant were fake instead. */
    @Test
    void registerClaimReturns404ForAnUnknownPolicy() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-CT-NOPOLICY-01");

        mockMvc.perform(post("/claims")
                .with(staffOf(tenantId))
                .header("Idempotency-Key", "ct-http-nopolicy-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"policyNumber":"POL-NOTFOUND01","claimantPartyId":"%s","claimType":"DEATH","dateOfEvent":"%s",
                     "details":{"claimType":"DEATH","causeOfDeath":"Cardiac arrest","placeOfDeath":"Dar es Salaam",
                     "dateOfDeath":"%s","attendingPhysician":"Dr. Kessy"}}
                    """.formatted(fixture.applicantId(), LocalDate.now(), LocalDate.now())))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("POLICY_NOT_FOUND"));
    }

    /** Also covered at a narrower granularity by {@code ClaimControllerValidationContractTest}
     * (which asserts only the error shape); included here too, alongside this file's other
     * {@code POST /claims} scenarios, so this file stands alone as the full-surface falsifiability
     * gate the task brief calls for. {@code Idempotency-Key} is spec-required (openapi-claims.yaml
     * marks the header {@code required: true}), so a request that omits it entirely is itself
     * schema-invalid -- {@code openApi().isValid(SPEC_PATH)} is omitted for exactly the same reason
     * the brief's own schema-invalid-body gotcha describes, just on the request's header instead
     * of its body. */
    @Test
    void registerClaimReturns400WhenIdempotencyKeyHeaderIsMissing() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-CT-NOIDEM-01");

        mockMvc.perform(post("/claims")
                .with(staffOf(tenantId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"policyNumber":"POL-DOESNOTEXIST","claimantPartyId":"%s","claimType":"DEATH","dateOfEvent":"%s",
                     "details":{"claimType":"DEATH","causeOfDeath":"Cardiac arrest","placeOfDeath":"Dar es Salaam",
                     "dateOfDeath":"%s","attendingPhysician":"Dr. Kessy"}}
                    """.formatted(fixture.applicantId(), LocalDate.now(), LocalDate.now())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.traceId").exists());
    }

    /**
     * M6 final-review fix (I4): the ONLY test in the suite that puts a valid
     * {@code DisabilityClaimDetails} on the wire in BOTH directions with
     * {@code openApi().isValid(SPEC_PATH)} applied. The pre-existing DISABILITY payload in this file
     * ({@code registerClaimReturns422WhenDetailsClaimTypeDisagreesWithDeclaredType}) is a
     * deliberately-invalid 422 REQUEST, so it never exercised the response side at all -- which is
     * how {@code impairmentPercent} came to serialize as a JSON number ({@code 50.00}) while
     * openapi-claims.yaml declares {@code type: string, pattern: '^\d+(\.\d{1,2})?$'}, in line with
     * openapi-common.yaml's Money convention of never putting a decimal on the wire as a binary
     * float. Requests worked (Jackson coerces String -> BigDecimal inbound), so nothing failed; every
     * response silently violated the spec.
     *
     * <p>Both halves are asserted deliberately: the 201 response body from {@code POST /claims}, and
     * a fresh {@code GET /claims/{id}} that re-reads the value back out of JSONB (proving the string
     * form round-trips through persistence, not just through one in-memory serialization). The
     * jsonPath assertions compare against a STRING, so a regression to a bare number fails here even
     * if the validator's pattern check were ever relaxed.
     *
     * <p>Both halves also carry {@link SpecTypeConformance#matchesDeclaredTypes} alongside
     * {@code openApi().isValid(SPEC_PATH)}: measured empirically, {@code isValid} enforces
     * required/enum/pattern/additionalProperties but NOT primitive JSON types -- a {@code type:
     * string} field emitted as a number, or a {@code type: boolean} emitted as a string, both report
     * {@code hasErrors=false}. That matcher covers exactly that difference, generically, for every
     * field of the schema rather than the hand-picked ones asserted below; see its javadoc.
     */
    @Test
    void registerAndGetADisabilityClaimBothValidateAgainstTheSpec() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-CT-DISABILITY-01");
        String policyNumber = issuePolicy(tenantId, fixture);
        LocalDate onsetDate = LocalDate.now().minusDays(3);

        String created = mockMvc.perform(post("/claims")
                .with(staffOf(tenantId))
                .header("Idempotency-Key", "ct-http-disability-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"policyNumber":"%s","claimantPartyId":"%s","claimType":"DISABILITY","dateOfEvent":"%s",
                     "details":{"claimType":"DISABILITY","disabilityType":"Loss of limb","onsetDate":"%s",
                     "permanent":true,"impairmentPercent":"62.50"}}
                    """.formatted(policyNumber, fixture.applicantId(), onsetDate, onsetDate)))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "ClaimView"))
            .andExpect(jsonPath("$.claimType").value("DISABILITY"))
            .andExpect(jsonPath("$.details.claimType").value("DISABILITY"))
            // A JSON string, not a number: jsonPath's value() is type-strict, so 62.50-as-number
            // fails this even before the spec validator's pattern check gets a look in.
            .andExpect(jsonPath("$.details.impairmentPercent").value("62.50"))
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        String claimId = JsonPath.read(created, "$.claimId");

        mockMvc.perform(get("/claims/" + claimId).with(staffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "ClaimView"))
            .andExpect(jsonPath("$.details.impairmentPercent").value("62.50"))
            .andExpect(jsonPath("$.details.permanent").value(true))
            .andExpect(jsonPath("$.details.disabilityType").value("Loss of limb"));
    }

    // ============================================================================================
    // GET /claims/{claimId}
    // ============================================================================================

    @Test
    void getClaimReturns200ForStaff() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-CT-GET-01");
        String policyNumber = issuePolicy(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture.applicantId(), policyNumber);

        mockMvc.perform(get("/claims/" + claimId).with(staffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.claimId").value(claimId.toString()))
            .andExpect(jsonPath("$.status").value("REGISTERED"));
    }

    @Test
    void getClaimReturns200ForTheOwningCustomer() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-CT-GET-02");
        String policyNumber = issuePolicy(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture.applicantId(), policyNumber);

        mockMvc.perform(get("/claims/" + claimId).with(customerOf(tenantId, fixture.applicantId())))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.claimId").value(claimId.toString()));
    }

    /** Non-vacuous: the claim genuinely exists and belongs to a DIFFERENT party in the SAME
     * tenant, so a broken/missing {@code enforceCustomerOwnClaimOnly} would return 200, not an
     * incidental 404. This is the highest-value security assertion in this file. */
    @Test
    void getClaimReturns403ForADifferentCustomerSameTenant() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-CT-GET-03");
        String policyNumber = issuePolicy(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture.applicantId(), policyNumber);

        mockMvc.perform(get("/claims/" + claimId).with(customerOf(tenantId, UUID.randomUUID())))
            .andExpect(status().isForbidden())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    void getClaimReturns404ForAnUnknownId() throws Exception {
        mockMvc.perform(get("/claims/" + UUID.randomUUID()).with(staffOf(UUID.randomUUID())))
            .andExpect(status().isNotFound())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("CLAIM_NOT_FOUND"));
    }

    /** Mirrors {@code PolicyContractTest.getPolicyRejectsCrossTenantReadWithNotFoundForAntiEnumeration}
     * exactly, applied to claims: a SAME-tenant ownership mismatch (above) is a 403, but a
     * DIFFERENT-tenant read of someone else's claim must be a 404, NOT a 403 -- a 403 across a
     * tenant boundary would leak "this claimId exists (just not for you)". The customer's
     * party_id claim below deliberately MATCHES the real claimant -- proving the 404 comes from
     * {@code ClaimsApiImpl.findOrThrow}'s tenant-scoped query (a different tenantId finds no row
     * at all) and not from {@code enforceCustomerOwnClaimOnly}, which this JWT would otherwise
     * pass. */
    @Test
    void getClaimRejectsCrossTenantReadWithNotFoundForAntiEnumeration() throws Exception {
        UUID ownerTenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(ownerTenantId, "CLAIMS-CT-GET-04");
        String policyNumber = issuePolicy(ownerTenantId, fixture);
        UUID claimId = registerDeathClaim(ownerTenantId, fixture.applicantId(), policyNumber);
        UUID otherTenantId = UUID.randomUUID();

        mockMvc.perform(get("/claims/" + claimId).with(customerOf(otherTenantId, fixture.applicantId())))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("CLAIM_NOT_FOUND"));
    }

    // ============================================================================================
    // GET /claims
    // ============================================================================================

    @Test
    void listClaimsReturns200WithStatusFilterMatchingOnlyTheFilteredClaim() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-CT-LIST-01");
        String policyNumber = issuePolicy(tenantId, fixture);

        // Two claims on one policy, of DIFFERENT types: one death claim per life now refuses a
        // second DEATH here, and this test is about the status filter, not the claim type.
        UUID registeredClaimId = registerMaturityClaim(tenantId, fixture.applicantId(), policyNumber);
        UUID underAssessmentClaimId = registerDeathClaim(tenantId, fixture.applicantId(), policyNumber);
        submitAssessmentDirectly(tenantId, underAssessmentClaimId, "assessor-list-fixture");

        mockMvc.perform(get("/claims")
                .queryParam("status", "UNDER_ASSESSMENT")
                .queryParam("claimantPartyId", fixture.applicantId().toString())
                .with(staffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.items[?(@.claimId == '" + underAssessmentClaimId + "')]").exists())
            .andExpect(jsonPath("$.items[?(@.claimId == '" + registeredClaimId + "')]").doesNotExist());
    }

    // ============================================================================================
    // POST /claims/{claimId}/assessments
    // ============================================================================================

    @Test
    void submitAssessmentReturns201ForClaimsAssessor() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-CT-ASSESS-01");
        String policyNumber = issuePolicy(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture.applicantId(), policyNumber);

        mockMvc.perform(post("/claims/" + claimId + "/assessments")
                .with(assessorOf(tenantId, "assessor-http-1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"findings":"Consistent with cause of death",
                     "recommendedAmount":{"amount":"2000000.00","currencyCode":"TZS"},"fraudIndicator":false}
                    """))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.claimId").value(claimId.toString()))
            .andExpect(jsonPath("$.assessor").value("assessor-http-1"))
            // No name claim on this token, so none is invented -- and certainly not the subject.
            .andExpect(jsonPath("$.assessorName").doesNotExist())
            .andExpect(jsonPath("$.recommendedAmount.amount").value("2000000.00"));
    }

    /** The manager was shown "recommended by 1697c88f-78d8-…". The name comes from the
     * assessor's own token -- shaped as Keycloak really issues it, {@code name} beside
     * {@code preferred_username} -- is persisted, and reads back on the list the manager sees. */
    @Test
    void theAssessorIsNamedFromTheirTokenAndTheNameIsWhatTheManagerReads() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-CT-ASSESS-NAME-01");
        String policyNumber = issuePolicy(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture.applicantId(), policyNumber);

        mockMvc.perform(post("/claims/" + claimId + "/assessments")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CLAIMS_ASSESSOR"),
                        new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.subject("ac76ad34-d514-421e-8c39-694ce179b590")
                        .claim("tenant_id", tenantId.toString())
                        .claim("name", "Daudi Assessor")
                        .claim("preferred_username", "staff.assessor")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"findings":"Consistent with cause of death",
                     "recommendedAmount":{"amount":"2000000.00","currencyCode":"TZS"},"fraudIndicator":false}
                    """))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            // The subject is still what is stored as the identity -- separation of duties
            // compares it, and a name is neither unique nor stable.
            .andExpect(jsonPath("$.assessor").value("ac76ad34-d514-421e-8c39-694ce179b590"))
            .andExpect(jsonPath("$.assessorName").value("Daudi Assessor"));

        mockMvc.perform(get("/claims/" + claimId + "/assessments")
                .with(managerOf(tenantId, "manager-reading-names")))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$[0].assessorName").value("Daudi Assessor"));
    }

    @Test
    void anAssessorWithNoDisplayNameIsNamedByTheirUsername() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-CT-ASSESS-NAME-02");
        String policyNumber = issuePolicy(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture.applicantId(), policyNumber);

        mockMvc.perform(post("/claims/" + claimId + "/assessments")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CLAIMS_ASSESSOR"),
                        new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.subject("assessor-without-a-name")
                        .claim("tenant_id", tenantId.toString())
                        .claim("name", "  ")
                        .claim("preferred_username", "staff.assessor")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"findings":"Consistent with cause of death",
                     "recommendedAmount":{"amount":"2000000.00","currencyCode":"TZS"},"fraudIndicator":false}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.assessorName").value("staff.assessor"));
    }

    /** Through HTTP, so the documented 422 is proven to be the status actually served. */
    @Test
    void submitAssessmentReturns422ForARecommendationAboveTheCover() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-CT-ASSESS-CAP");
        String policyNumber = issuePolicy(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture.applicantId(), policyNumber);

        mockMvc.perform(post("/claims/" + claimId + "/assessments")
                .with(assessorOf(tenantId, "assessor-over-cover"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"findings":"Consistent with cause of death",
                     "recommendedAmount":{"amount":"50000000.00","currencyCode":"TZS"},"fraudIndicator":false}
                    """))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("CLAIM_VALIDATION_FAILED"));
    }

    /** Non-vacuous: the claim is real and REGISTERED, so a missing/broken
     * {@code @PreAuthorize("hasRole('CLAIMS_ASSESSOR')")} would return 201, not an incidental 404. */
    @Test
    void submitAssessmentReturns403ForACustomerToken() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-CT-ASSESS-02");
        String policyNumber = issuePolicy(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture.applicantId(), policyNumber);

        mockMvc.perform(post("/claims/" + claimId + "/assessments")
                .with(customerOf(tenantId, fixture.applicantId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"findings":"Should be rejected before reaching the service layer",
                     "recommendedAmount":{"amount":"2000000.00","currencyCode":"TZS"},"fraudIndicator":false}
                    """))
            .andExpect(status().isForbidden())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    // ============================================================================================
    // GET /claims/{claimId}/assessments
    // ============================================================================================

    /**
     * The read that closes separation of duties.
     *
     * <p>A manager may not decide a claim they assessed, so the decider is always somebody else —
     * and until this endpoint existed there was no way for that person to see the recommendation
     * they were being asked to approve. The assertion is deliberately made with a MANAGER token,
     * not an assessor one: an endpoint only the assessor could read would leave the actual gap
     * exactly where it was.
     */
    @Test
    void listAssessmentsLetsTheDecidingManagerSeeWhatTheAssessorRecommended() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-CT-ASSESS-LIST-01");
        String policyNumber = issuePolicy(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture.applicantId(), policyNumber);
        submitAssessmentDirectly(tenantId, claimId, "assessor-who-will-not-decide");

        mockMvc.perform(get("/claims/" + claimId + "/assessments")
                .with(managerOf(tenantId, "manager-who-decides")))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$[0].assessor").value("assessor-who-will-not-decide"))
            .andExpect(jsonPath("$[0].findings").value("Fixture findings"))
            // Money, not a number -- this is the figure the approval form prefills from.
            .andExpect(jsonPath("$[0].recommendedAmount.amount").value("2000000.00"))
            .andExpect(jsonPath("$[0].recommendedAmount.currencyCode").value("TZS"))
            .andExpect(jsonPath("$[0].fraudIndicator").value(false));
    }

    /** Findings and the fraud flag are internal. A customer must not read a scrutiny signal
     * recorded about themselves, and the claim here is real and assessed so a missing
     * {@code @PreAuthorize} would answer 200 rather than an incidental 404. */
    @Test
    void listAssessmentsReturns403ForACustomerToken() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-CT-ASSESS-LIST-02");
        String policyNumber = issuePolicy(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture.applicantId(), policyNumber);
        submitAssessmentDirectly(tenantId, claimId, "assessor-http-list");

        mockMvc.perform(get("/claims/" + claimId + "/assessments")
                .with(customerOf(tenantId, fixture.applicantId())))
            .andExpect(status().isForbidden())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    // ============================================================================================
    // GET /claims/{claimId}/claimable-cover
    // ============================================================================================

    /**
     * The ceiling, readable before it is exceeded.
     *
     * <p>2,000,000.00 is this fixture's sum assured — the SAME number {@code Claim.approve} bounds
     * an approval with. Asserting the value rather than merely the shape is the point: a response
     * that parsed but carried a different figure would be worse than no endpoint, because the
     * form prefills from it.
     */
    @Test
    void claimableCoverPublishesTheCeilingAnApprovalIsBoundedBy() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-CT-COVER-01");
        String policyNumber = issuePolicy(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture.applicantId(), policyNumber);

        mockMvc.perform(get("/claims/" + claimId + "/claimable-cover")
                .with(managerOf(tenantId, "manager-reading-cover")))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.claimableCover.amount").value("2000000.00"))
            .andExpect(jsonPath("$.claimableCover.currencyCode").value("TZS"));
    }

    /** An assessor needs it too: the recommendation they type is bounded by the same figure the
     * manager's approval is, so showing it to only one of the two would leave the other guessing. */
    @Test
    void claimableCoverIsReadableByTheAssessorAsWellAsTheManager() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-CT-COVER-02");
        String policyNumber = issuePolicy(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture.applicantId(), policyNumber);

        mockMvc.perform(get("/claims/" + claimId + "/claimable-cover")
                .with(assessorOf(tenantId, "assessor-reading-cover")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.claimableCover.amount").value("2000000.00"));
    }

    /** A plain staff token is not enough. Claims work is role-gated, and the cover on somebody's
     * death claim is not general staff reading. */
    @Test
    void claimableCoverReturns403ForAPlainStaffToken() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-CT-COVER-03");
        String policyNumber = issuePolicy(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture.applicantId(), policyNumber);

        mockMvc.perform(get("/claims/" + claimId + "/claimable-cover")
                .with(staffOf(tenantId)))
            .andExpect(status().isForbidden())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    // ============================================================================================
    // POST /claims/{claimId}/settlement-decision
    // ============================================================================================

    @Test
    void decideSettlementReturns202ForClaimsManager() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-CT-DECIDE-01");
        String policyNumber = issuePolicy(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture.applicantId(), policyNumber);
        submitAssessmentDirectly(tenantId, claimId, "assessor-decide-01");

        mockMvc.perform(post("/claims/" + claimId + "/settlement-decision")
                .with(managerOf(tenantId, "manager-decide-01")) // distinct from the assessor above -- SoD
                .header("Idempotency-Key", "ct-http-settle-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"approved":true,"approvedAmount":{"amount":"2000000.00","currencyCode":"TZS"},
                     "payeeRef":"MPESA-0712340009"}
                    """))
            .andExpect(status().isAccepted())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.status").value("SETTLEMENT_REQUESTED"))
            .andExpect(jsonPath("$.approvedAmount.amount").value("2000000.00"));
    }

    /** Non-vacuous: the claim is real and genuinely assessed (ready for a decision), so a
     * missing/broken {@code @PreAuthorize("hasRole('CLAIMS_MANAGER')")} would return 202, not an
     * incidental 404 -- and proves CLAIMS_ASSESSOR/CLAIMS_MANAGER are genuinely distinct roles,
     * not both just "staff". */
    @Test
    void decideSettlementReturns403ForClaimsAssessor() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-CT-DECIDE-02");
        String policyNumber = issuePolicy(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture.applicantId(), policyNumber);
        submitAssessmentDirectly(tenantId, claimId, "assessor-decide-02");

        mockMvc.perform(post("/claims/" + claimId + "/settlement-decision")
                .with(assessorOf(tenantId, "assessor-decide-02b"))
                .header("Idempotency-Key", "ct-http-settle-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"approved":true,"approvedAmount":{"amount":"2000000.00","currencyCode":"TZS"},
                     "payeeRef":"MPESA-0712340010"}
                    """))
            .andExpect(status().isForbidden())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    void decideSettlementReturns409WhenApprovingWithNoPriorAssessmentOnANonMaturityClaim() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-CT-DECIDE-03");
        String policyNumber = issuePolicy(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture.applicantId(), policyNumber); // no assessment

        mockMvc.perform(post("/claims/" + claimId + "/settlement-decision")
                .with(managerOf(tenantId, "manager-decide-03"))
                .header("Idempotency-Key", "ct-http-settle-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"approved":true,"approvedAmount":{"amount":"2000000.00","currencyCode":"TZS"},
                     "payeeRef":"MPESA-0712340011"}
                    """))
            .andExpect(status().isConflict())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("CLAIM_INVALID_STATE"));
    }

    /**
     * DEVIATION FROM THE TASK BRIEF, verified empirically rather than assumed: the brief predicts
     * "400 for a missing Idempotency-Key" on this operation, mirroring {@code POST /claims}'s own
     * genuine 400. That does not hold here -- {@code ClaimController.decideSettlement} declares
     * the header {@code required = false} and never rejects it itself (see that method's own
     * javadoc: "not rejected here for being absent on a REJECTION, since decideSettlement only
     * requires it when approved is true -- already enforced, with the correct 422, by
     * ClaimsApiImpl itself"). openapi-claims.yaml agrees: this operation's response set is
     * 202/401/403/404/409/422 -- 400 is not even a documented possibility. The genuinely reachable
     * status for a missing key on an APPROVAL is 422 CLAIM_VALIDATION_FAILED, asserted below.
     * {@code openApi().isValid(SPEC_PATH)} is omitted because the header is spec-required
     * ({@code required: true}), so a request that omits it is itself schema-invalid -- the same
     * schema-invalid-request gotcha as {@code registerClaimReturns400WhenIdempotencyKeyHeaderIsMissing}.
     * The claim is genuinely assessed (count > 0) and decided by someone other than the assessor,
     * isolating this from the 409 count-guard and the separation-of-duties 422 above it in
     * {@code ClaimsApiImpl.decideSettlement}'s check order.
     */
    @Test
    void decideSettlementReturns422WhenIdempotencyKeyHeaderIsMissingOnApproval() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-CT-DECIDE-04");
        String policyNumber = issuePolicy(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture.applicantId(), policyNumber);
        submitAssessmentDirectly(tenantId, claimId, "assessor-decide-04");

        mockMvc.perform(post("/claims/" + claimId + "/settlement-decision")
                .with(managerOf(tenantId, "manager-decide-04")) // distinct from the assessor -- SoD
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"approved":true,"approvedAmount":{"amount":"2000000.00","currencyCode":"TZS"},
                     "payeeRef":"MPESA-0712340012"}
                    """))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errorCode").value("CLAIM_VALIDATION_FAILED"));
    }

    // ============================================================================================
    // POST /claims/{claimId}/reopen
    // ============================================================================================

    @Test
    void reopenClaimReturns200ForClaimsManagerOnARejectedClaim() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-CT-REOPEN-01");
        String policyNumber = issuePolicy(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture.applicantId(), policyNumber);
        submitAssessmentDirectly(tenantId, claimId, "assessor-reopen-01");
        decideSettlementDirectly(tenantId, claimId, false, "manager-reopen-01"); // -> REJECTED

        mockMvc.perform(post("/claims/" + claimId + "/reopen")
                .with(managerOf(tenantId, "manager-reopen-01b"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"reason":"New evidence submitted"}
                    """))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.status").value("REOPENED"));
    }

    @Test
    void reopenClaimReturns409OnARegisteredClaim() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-CT-REOPEN-02");
        String policyNumber = issuePolicy(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture.applicantId(), policyNumber); // still REGISTERED

        mockMvc.perform(post("/claims/" + claimId + "/reopen")
                .with(managerOf(tenantId, "manager-reopen-02"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"reason":"Attempted reopen of a claim that was never rejected or settled"}
                    """))
            .andExpect(status().isConflict())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("CLAIM_INVALID_STATE"));
    }

    // ============================================================================================
    // POST /claims/{claimId}/evidence, GET /claims/{claimId}/evidence
    // ============================================================================================

    @Test
    void attachEvidenceReturns201ForMultipartUploadAndListsItAfterward() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-CT-EVIDENCE-01");
        String policyNumber = issuePolicy(tenantId, fixture);
        UUID claimId = registerMaturityClaim(tenantId, fixture.applicantId(), policyNumber);

        // openApi().isValid(SPEC_PATH) is deliberately omitted on this POST: verified empirically
        // that com.atlassian.oai.validator's MockMvc adapter cannot introspect a multipart/
        // form-data request body at all here -- it reports "validation.request.body.missing: A
        // request body is required but none found" even though a real file part is genuinely
        // present and the upload genuinely succeeds (confirmed by the assertions below and by the
        // follow-up GET, which DOES carry the matcher since it is an ordinary JSON response with
        // no multipart request to introspect). A known limitation of that MockMvc integration
        // with multipart bodies, not an application defect -- the same class of "matcher can't
        // see this" gap as the schema-invalid-body gotcha elsewhere in this file, just triggered
        // by the library's multipart handling instead of a deliberately invalid payload.
        String response = mockMvc.perform(multipart("/claims/" + claimId + "/evidence")
                .file(new MockMultipartFile("file", "maturity-certificate.pdf", "application/pdf",
                    "evidence-bytes".getBytes(StandardCharsets.UTF_8)))
                .part(new MockPart("description", "Maturity certificate scan".getBytes(StandardCharsets.UTF_8)))
                .with(customerOf(tenantId, fixture.applicantId())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.claimId").value(claimId.toString()))
            .andExpect(jsonPath("$.description").value("Maturity certificate scan"))
            .andReturn().getResponse().getContentAsString();
        String documentRef = JsonPath.read(response, "$.documentRef");

        mockMvc.perform(get("/claims/" + claimId + "/evidence").with(customerOf(tenantId, fixture.applicantId())))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$[0].documentRef").value(documentRef));
    }

    /** The evidence list printed the uploader's Keycloak subject. The name comes from the
     * uploader's own token -- here the claimant's, since customers attach their own evidence --
     * and reads back on the list staff see. */
    @Test
    void theUploaderIsNamedFromTheirTokenOnTheEvidenceList() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-CT-EVIDENCE-NAME");
        String policyNumber = issuePolicy(tenantId, fixture);
        UUID claimId = registerMaturityClaim(tenantId, fixture.applicantId(), policyNumber);

        // No openApi() matcher on the multipart POST -- see the 201 test above.
        mockMvc.perform(multipart("/claims/" + claimId + "/evidence")
                .file(new MockMultipartFile("file", "maturity-certificate.pdf", "application/pdf",
                    "evidence-bytes".getBytes(StandardCharsets.UTF_8)))
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
                    .jwt(builder -> builder.subject("5d1f2c3a-claimant-subject")
                        .claim("tenant_id", tenantId.toString())
                        .claim("party_id", fixture.applicantId().toString())
                        .claim("name", "Amina Claimant")
                        .claim("preferred_username", "amina"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.uploadedBy").value("5d1f2c3a-claimant-subject"))
            .andExpect(jsonPath("$.uploadedByName").value("Amina Claimant"));

        mockMvc.perform(get("/claims/" + claimId + "/evidence").with(staffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$[0].uploadedByName").value("Amina Claimant"));
    }

    /** Non-vacuous: the claim is real, and the multipart body is well-formed (a valid file part
     * is present), so a missing/broken {@code enforceCustomerOwnClaimOnly} in
     * {@code ClaimEvidenceController.attachEvidence} would return 201, not an incidental 404/400. */
    @Test
    void attachEvidenceReturns403ForANonOwningCustomer() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-CT-EVIDENCE-02");
        String policyNumber = issuePolicy(tenantId, fixture);
        UUID claimId = registerMaturityClaim(tenantId, fixture.applicantId(), policyNumber);

        // openApi().isValid(SPEC_PATH) omitted here too -- same multipart-body-introspection
        // limitation as the 201 test above, verified the same way.
        mockMvc.perform(multipart("/claims/" + claimId + "/evidence")
                .file(new MockMultipartFile("file", "maturity-certificate.pdf", "application/pdf",
                    "evidence-bytes".getBytes(StandardCharsets.UTF_8)))
                .with(customerOf(tenantId, UUID.randomUUID()))) // a DIFFERENT party, same tenant
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    // --- Staff list improvements: newest-first default sort, q free-text search ------------------

    @Test
    void listClaimsOrdersNewestCreatedFirstByDefault() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "SORT-ORDER-PRODUCT");
        // Two distinct policies (not two claims on one policy, to sidestep any
        // undocumented one-claim-per-policy assumption elsewhere in this domain) --
        // both real, both against the same real applicant/product fixture.
        String policyA = issuePolicy(tenantId, fixture);
        String policyB = issuePolicy(tenantId, fixture);
        UUID claimId1 = registerDeathClaim(tenantId, fixture.applicantId(), policyA);
        UUID claimId2 = registerDeathClaim(tenantId, fixture.applicantId(), policyB);

        mockMvc.perform(get("/claims")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].claimId").value(claimId2.toString()))
            .andExpect(jsonPath("$.items[1].claimId").value(claimId1.toString()));
    }

    @Test
    void listClaimsByQMatchesACaseInsensitiveSubstringOfPolicyNumber() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "Q-SEARCH-CLAIM-PRODUCT");
        String policyNumber = issuePolicy(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture.applicantId(), policyNumber);
        // A second real claim against a DIFFERENT policy in the same tenant --
        // without it, an IGNORED q would still return "everything in this fresh
        // tenant" (1 item), passing for the wrong reason.
        String otherPolicyNumber = issuePolicy(tenantId, fixture);
        registerDeathClaim(tenantId, fixture.applicantId(), otherPolicyNumber);

        // Deliberately lowercased query against a real POL-XXXXXXXX (uppercase-hex)
        // policy number -- falsifies "ILIKE is inherently case-insensitive" against a
        // real row rather than trusting the SQL.
        mockMvc.perform(get("/claims")
                .queryParam("q", policyNumber.toLowerCase())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].claimId").value(claimId.toString()));
    }

    @Test
    void listClaimsByQReturnsEmptyForNoMatches() throws Exception {
        UUID tenantId = UUID.randomUUID();
        mockMvc.perform(get("/claims")
                .queryParam("q", "NoClaimAnywhereIsAgainstThisExactNonsensePolicy12345")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
                    .jwt(builder -> builder.claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(0));
    }
    /**
     * Separation of duties, asserted rather than arranged around.
     *
     * Every other test on this class satisfies the rule by using two different subjects, one of
     * them commented "distinct from the assessor above -- SoD". None of them proved the rule
     * fires. Deleting the check in `ClaimsApiImpl.decideSettlement` would have left this whole
     * suite green: the condition was avoided, never exercised.
     *
     * It matters more now than it did. The check is on the PERSON, not the role, and until the
     * ADMIN role was given a capability of its own, `staff.admin` -- the only seeded identity
     * holding both CLAIMS_ASSESSOR and CLAIMS_MANAGER -- was never used by anything. It is the
     * only identity that can reach this violation at all, which is exactly why nothing caught
     * that its token could not even authenticate.
     *
     * The identity here carries BOTH roles, so the controller's `@PreAuthorize` passes and the
     * refusal can only be coming from the domain rule under test.
     */
    @Test
    void theSamePersonCannotAssessAClaimAndThenDecideIt() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "CLAIMS-CT-SOD-01");
        String policyNumber = issuePolicy(tenantId, fixture);
        UUID claimId = registerDeathClaim(tenantId, fixture.applicantId(), policyNumber);

        String oneperson = "staff-wearing-both-hats";
        submitAssessmentDirectly(tenantId, claimId, oneperson);

        mockMvc.perform(post("/claims/" + claimId + "/settlement-decision")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"),
                        new SimpleGrantedAuthority("ROLE_CLAIMS_ASSESSOR"),
                        new SimpleGrantedAuthority("ROLE_CLAIMS_MANAGER"))
                    .jwt(builder -> builder.subject(oneperson).claim("tenant_id", tenantId.toString())))
                .header("Idempotency-Key", "ct-http-sod-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"approved":true,"approvedAmount":{"amount":"2000000.00","currencyCode":"TZS"},
                     "payeeRef":"MPESA-0712340011"}
                    """))
            // 422, not 403: the caller genuinely holds CLAIMS_MANAGER. What is wrong is the
            // combination of this person and this claim, which is a business rule and not an
            // authorisation failure -- and the distinction is what tells a real manager holding
            // both roles that they need a colleague rather than a permission.
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Separation of duties")));

        // The claim is still decidable by somebody else, so the refusal above is about the person
        // and has not wedged the claim into a state nobody can move it out of.
        mockMvc.perform(post("/claims/" + claimId + "/settlement-decision")
                .with(managerOf(tenantId, "a-different-manager"))
                .header("Idempotency-Key", "ct-http-sod-ok-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"approved":true,"approvedAmount":{"amount":"2000000.00","currencyCode":"TZS"},
                     "payeeRef":"MPESA-0712340012"}
                    """))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("SETTLEMENT_REQUESTED"));
    }
}
