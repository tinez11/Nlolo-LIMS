package tz.co.nlolo.lifeplatform.document;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.SpecTypeConformance;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.claims.api.ClaimType;
import tz.co.nlolo.lifeplatform.claims.api.ClaimView;
import tz.co.nlolo.lifeplatform.claims.api.ClaimsApi;
import tz.co.nlolo.lifeplatform.claims.api.MaturityClaimDetails;
import tz.co.nlolo.lifeplatform.document.api.DocumentApi;
import tz.co.nlolo.lifeplatform.document.api.DocumentType;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.product.api.*;
import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 6: HTTP-level contract coverage for {@code DocumentController} (staff-only generic
 * download/metadata, Task 4) and {@code ClaimEvidenceController.downloadEvidence} (the one
 * evidence-download-by-ref path {@code ClaimsContractTest} does not already cover -- that class
 * exercises {@code POST}/{@code GET .../evidence} but never the single-file
 * {@code GET .../evidence/{documentRef}} endpoint) against {@code api/openapi/openapi-document.yaml}.
 * Follows {@code FinaccountingContractTest}'s harness shape: {@code @Testcontainers} + {@code
 * @AutoConfigureMockMvc} + {@code @SpringBootTest(classes = Application.class, webEnvironment =
 * MOCK)}, {@code jwt()} post-processors, and {@code openApi().isValid(SPEC_PATH)} paired with
 * {@link SpecTypeConformance#matchesDeclaredTypes} on the one JSON response this spec declares
 * ({@code GET /documents/{ref}/metadata} -- the two download endpoints return raw bytes under one
 * of the four allowed binary media types, so only {@code isValid(...)} applies to those).
 *
 * <p>Unlike {@code FinaccountingContractTest}, this class needs REAL object storage, not just
 * Postgres: {@code DocumentApiImpl.download}/{@code upload} go straight through {@code
 * MinioDocumentStorage} to MinIO, so a document fixture seeded without an object actually written
 * would 200 on metadata but fail the byte-content download. This mirrors {@code
 * ClaimEvidenceIntegrationTest}'s/{@code DocumentApiIntegrationTest}'s container/bucket setup (a
 * {@code MinIOContainer} with buckets created by hand in {@code @BeforeAll}, since the
 * Testcontainers container never runs {@code infra/docker-compose.yml}'s minio-init job) -- only
 * the two buckets this class's fixtures actually touch ({@code policy-documents} for the generic
 * document, {@code claim-evidence} for the claim's) are created, mirroring {@code
 * ClaimsContractTest}'s own "only what's used" bucket-creation comment.
 *
 * <p><b>Migration list mirrors {@code ClaimsContractTest}'s full policy/product/party chain</b>
 * (party/product/underwriting/policy schemas), extended with {@code document/V1}-{@code V2} on top
 * of {@code ClaimEvidenceIntegrationTest}'s own document+claims list: a REAL claim registered
 * through {@code ClaimsApi.registerClaim} (not a repository shortcut -- {@code claims.domain}/
 * {@code claims.infrastructure} are {@code public} and technically reachable even from here, but
 * going through {@code claims}'s named {@code .api} interface instead is this platform's own
 * established convention, not something a compiler or a structural test enforces for test code;
 * see below) validates against a REAL, in-force policy, which in turn needs a real product and
 * applicant -- {@code buildFixture}/{@code
 * issuePolicy}/{@code registerMaturityClaim} below are copied verbatim from {@code
 * ClaimsContractTest}'s own identically-named helpers for exactly that reason, not reinvented.
 * {@code party::api}/{@code product::api}/{@code policy::api}/{@code claims::api} are all
 * NAMED-INTERFACE ({@code .api}) packages -- this is a design choice made to follow established
 * platform convention (every {@code *ContractTest} on this platform seeds fixtures through public
 * {@code .api} types, never a sibling module's {@code .domain}/{@code .infrastructure}), NOT
 * something {@code ModularityTests} verifies either way. {@code ApplicationModules.of(...)} (and
 * this platform's own {@code NoCrossModuleJoinTest}, which applies {@code
 * ImportOption.Predefined.DO_NOT_INCLUDE_TESTS} explicitly) both scan main-source classes only --
 * spring-modulith-core's {@code ApplicationModules} bakes in {@code ImportOption$DoNotIncludeTests}
 * -- so {@code ModularityTests} cannot see this class, or any other test-source file, at all. It
 * would pass identically whether this fixture seeded through {@code ClaimsApi} (what it does) or
 * reached directly into {@code claims.domain.Claim}/{@code claims.infrastructure.ClaimRepository}
 * (both {@code public}, so nothing would stop that either) -- test-source cross-module reaches are
 * simply outside what Spring Modulith's structural checks enforce on this platform today.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class DocumentContractTest {

    private static final String SPEC_PATH = "api/openapi/openapi-document.yaml";

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
            "db-migrations/claims/V1__create_claims_schema.sql",
            "db-migrations/claims/V2__grants_rls_money_checks_evidence_and_settlement_columns.sql",
            "db-migrations/claims/V3__registration_idempotency_key.sql",
            "db-migrations/claims/V5__claim_policy_member.sql",
            "db-migrations/document/V1__create_document_schema.sql",
            "db-migrations/document/V2__add_content_type_and_file_name.sql");

        MinioClient minioClient = MinioClient.builder()
            .endpoint(MINIO.getS3URL())
            .credentials(MINIO.getUserName(), MINIO.getPassword())
            .build();
        minioClient.makeBucket(MakeBucketArgs.builder().bucket("policy-documents").build());
        minioClient.makeBucket(MakeBucketArgs.builder().bucket("claim-evidence").build());
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private DocumentApi documentApi;
    @Autowired private PartyApi partyApi;
    @Autowired private ProductApi productApi;
    @Autowired private PolicyApi policyApi;
    @Autowired private ClaimsApi claimsApi;

    @AfterEach
    void resetAfterEach() {
        TenantContext.clear();
    }

    // --- token shapes (financeStaffOf/agentOf copied verbatim from
    // FinaccountingContractTest:120-138; customerOf newly introduced per the brief) -------------

    private static RequestPostProcessor financeStaffOf(UUID tenantId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"),
                                 new SimpleGrantedAuthority("ROLE_FINANCE_OFFICER"))
            .jwt(builder -> builder.subject("finance-officer").claim("tenant_id", tenantId.toString()));
    }

    private static RequestPostProcessor agentOf(UUID tenantId, UUID partyId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
            .jwt(builder -> builder.subject("agent").claim("tenant_id", tenantId.toString())
                .claim("party_id", partyId.toString()));
    }

    private static RequestPostProcessor customerOf(UUID tenantId, UUID partyId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
            .jwt(builder -> builder.subject("customer").claim("tenant_id", tenantId.toString())
                .claim("party_id", partyId.toString()));
    }

    // --- fixture plumbing, copied verbatim from ClaimsContractTest -----------------------------

    private record Fixture(UUID applicantId, UUID productId, UUID productVersionId) {}

    private Fixture buildFixture(UUID tenantId, String productCode) {
        TenantContext.set(tenantId);
        PartyView applicant = partyApi.registerIndividual("Document Contract Test Applicant " + productCode,
            LocalDate.of(1985, 3, 1), "+25571700" + String.format("%04d", Math.abs(productCode.hashCode() % 10000)),
            null, "test-agent");
        ProductSummaryView product = productApi.createProduct(productCode, "Document Contract Test Product",
            ProductCategory.TERM_LIFE, "TZS", "actuary");
        productApi.publishVersion(product.productId(), IfrsMeasurementModel.PAA, LocalDate.now(), null,
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "30-39", BigDecimal.ONE, 30, 39),
                    new ProductApi.RatingFactorInput(FactorType.SUM_ASSURED_BAND, "LOW", BigDecimal.ONE)),
            List.of(new ProductApi.BenefitInput(BenefitType.DEATH, "SUM_ASSURED")),
            null, "actuary");
        ProductSnapshotView snapshot = productApi.getActiveSnapshot(product.productId(), LocalDate.now());
        TenantContext.clear();
        return new Fixture(applicant.partyId(), product.productId(), snapshot.productVersionId());
    }

    private String issuePolicy(UUID tenantId, Fixture fixture) {
        TenantContext.set(tenantId);
        PolicyApi.IssueRequest request = new PolicyApi.IssueRequest(fixture.applicantId(), fixture.productId(),
            fixture.productVersionId(), new BigDecimal("2000000"), "TZS", new BigDecimal("40000.00"), "TZS",
            "MONTHLY", null, List.of(), "Document contract test");
        String policyNumber = policyApi.issuePolicy(null, request, "test-staff").policyNumber();
        // Cover starts with the first premium. This fixture needs a policy on risk.
        policyApi.activateOnFirstPremium(policyNumber);
        TenantContext.clear();
        return policyNumber;
    }

    /** MATURITY needs no assessment to exist as a REGISTERED claim -- the only state this class's
     * evidence-download test needs. */
    private UUID registerMaturityClaim(UUID tenantId, UUID claimantId, String policyNumber) {
        TenantContext.set(tenantId);
        LocalDate dateOfEvent = LocalDate.now().minusDays(1);
        ClaimView view = claimsApi.registerClaim(
            new ClaimsApi.RegisterClaimRequest(policyNumber, null, claimantId, ClaimType.MATURITY, dateOfEvent,
                new MaturityClaimDetails(dateOfEvent)),
            "dct-reg-" + UUID.randomUUID(), "claims-staff-fixture");
        TenantContext.clear();
        return view.claimId();
    }

    /** Seeds an arbitrary document under the given tenant with no owning aggregate, bypassing any
     * "does this belong to you?" concept -- exactly what {@code DocumentController}'s staff-only
     * endpoints are for. Same-module {@code DocumentApi}/{@code DocumentType} usage, no cross-module
     * concern here. */
    private String seedGenericDocument(UUID tenantId, byte[] content) {
        TenantContext.set(tenantId);
        // A REAL media type, matching the "statement.pdf" filename. This fixture used to be
        // application/octet-stream only because openapi-document.yaml's two binary 200 responses
        // declared that single content type while their own prose promised the media type recorded
        // at upload -- so a realistic fixture failed openApi().isValid(...) with
        // validation.response.contentType.notAllowed. The spec now declares the same closed set
        // ClaimEvidenceController's upload allowlist enforces (image/jpeg, image/png,
        // application/pdf, application/octet-stream), so the workaround is gone and this class now
        // validates a real media type against the spec, as it should have from the start.
        String ref = documentApi.upload("ops:fixture", DocumentType.POLICY_DOCUMENT, "test-uploader",
            new ByteArrayInputStream(content), content.length, "application/pdf", "statement.pdf");
        TenantContext.clear();
        return ref;
    }

    /** Attaches one piece of evidence to a real claim, seeded directly through {@code DocumentApi}/
     * {@code ClaimsApi} rather than the multipart HTTP endpoint (already covered end-to-end by
     * {@code ClaimsContractTest.attachEvidenceReturns201ForMultipartUploadAndListsItAfterward}) --
     * this class's own target is the single-file download endpoint, not the upload one. */
    private String attachEvidence(UUID tenantId, UUID claimId, byte[] evidenceContent) {
        TenantContext.set(tenantId);
        // image/jpeg, matching the .jpg filename and what the live seeded data actually holds --
        // see seedGenericDocument's comment: the spec now declares the whole allowed set, so a
        // realistic media type is validated here rather than worked around.
        String evidenceRef = documentApi.upload("claim:" + claimId, DocumentType.CLAIM_EVIDENCE,
            "test-uploader", new ByteArrayInputStream(evidenceContent), evidenceContent.length,
            "image/jpeg", "evidence.jpg");
        claimsApi.attachEvidence(claimId, evidenceRef, "Maturity certificate scan", "test-uploader");
        TenantContext.clear();
        return evidenceRef;
    }

    // ============================================================================================
    // GET /documents/{documentRef}
    // ============================================================================================

    @Test
    void downloadReturns200ForStaffAnd403ForCustomerAndAgentAnd404ForAnUnknownRef() throws Exception {
        UUID tenantId = UUID.randomUUID();
        byte[] content = "generic-document-bytes".getBytes();
        String documentRef = seedGenericDocument(tenantId, content);

        mockMvc.perform(get("/documents/{documentRef}", documentRef).with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(content().bytes(content));

        // No fine-grained-role distinction on this endpoint (Task 4): bare REALM_STAFF gates it,
        // so both non-staff realms are denied identically -- 403, not merely "not the right role".
        mockMvc.perform(get("/documents/{documentRef}", documentRef).with(customerOf(tenantId, UUID.randomUUID())))
            .andExpect(status().isForbidden());

        mockMvc.perform(get("/documents/{documentRef}", documentRef).with(agentOf(tenantId, UUID.randomUUID())))
            .andExpect(status().isForbidden());

        mockMvc.perform(get("/documents/{documentRef}", UUID.randomUUID().toString()).with(financeStaffOf(tenantId)))
            .andExpect(status().isNotFound())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    // ============================================================================================
    // GET /documents/{documentRef}/metadata
    // ============================================================================================

    @Test
    void metadataReturns200WithTheDeclaredShapeForStaffAnd403ForACustomer() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String documentRef = seedGenericDocument(tenantId, "metadata-fixture-bytes".getBytes());

        mockMvc.perform(get("/documents/{documentRef}/metadata", documentRef).with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "DocumentMetadataView"))
            .andExpect(jsonPath("$.documentRef").value(documentRef))
            .andExpect(jsonPath("$.documentType").value("POLICY_DOCUMENT"));

        mockMvc.perform(get("/documents/{documentRef}/metadata", documentRef).with(customerOf(tenantId, UUID.randomUUID())))
            .andExpect(status().isForbidden());
    }

    // ============================================================================================
    // GET /claims/{claimId}/evidence/{documentRef}
    // ============================================================================================

    @Test
    void downloadEvidenceReturns200ForTheOwningCustomerAndForStaff() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = buildFixture(tenantId, "DOCUMENT-CT-EVIDENCE-01");
        String policyNumber = issuePolicy(tenantId, fixture);
        UUID claimId = registerMaturityClaim(tenantId, fixture.applicantId(), policyNumber);
        byte[] evidenceContent = "owning-customer-evidence-bytes".getBytes();
        String evidenceRef = attachEvidence(tenantId, claimId, evidenceContent);

        mockMvc.perform(get("/claims/{claimId}/evidence/{documentRef}", claimId, evidenceRef)
                .with(customerOf(tenantId, fixture.applicantId())))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(content().bytes(evidenceContent));

        mockMvc.perform(get("/claims/{claimId}/evidence/{documentRef}", claimId, evidenceRef)
                .with(financeStaffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(content().bytes(evidenceContent));
    }
}
