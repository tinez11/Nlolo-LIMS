package tz.co.nlolo.lifeplatform.claims;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.claims.api.ClaimType;
import tz.co.nlolo.lifeplatform.claims.api.MaturityClaimDetails;
import tz.co.nlolo.lifeplatform.claims.domain.Claim;
import tz.co.nlolo.lifeplatform.claims.infrastructure.ClaimRepository;
import tz.co.nlolo.lifeplatform.document.api.DocumentApi;
import tz.co.nlolo.lifeplatform.document.api.DocumentType;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 3's three non-negotiable behaviours for GET /claims/{claimId}/evidence/{documentRef}.
 * Container setup, migration list, and fixture idioms copied verbatim from
 * {@code ClaimEvidenceIntegrationTest} (a Postgres superuser connection plus a MinIOContainer with
 * buckets created manually in {@code @BeforeAll}, since the container never runs
 * infra/docker-compose.yml's minio-init job); HTTP-level role/ownership assertions copied from
 * {@code ClaimsContractTest}'s {@code jwt()}-request-post-processor idiom.
 *
 * <p><b>Why the cross-claim test matters more than the others.</b> Endpoint 1 takes TWO
 * caller-supplied identifiers. Verifying only the claim authorizes one of them while the OTHER
 * still selects the resource -- so a customer who legitimately owns claim A could pass their own
 * claimId with a documentRef belonging to a stranger's claim B and receive the file. That is
 * exactly the nested-resource IDOR M7 shipped in AgentController (it verified agentId and never
 * that statementId belonged to it), which the M7 final review caught only after 26/26 contract
 * tests were already green -- the defect lived entirely in the untested COMBINATION of two
 * individually-correct checks. Every other authorization test in this class passes with the
 * ownerContext check deleted; this one does not.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ClaimEvidenceDownloadTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Container
    static MinIOContainer MINIO = new MinIOContainer("minio/minio:latest");

    private static MinioClient minioClient;

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
            "db-migrations/document/V1__create_document_schema.sql",
            "db-migrations/document/V2__add_content_type_and_file_name.sql",
            "db-migrations/claims/V1__create_claims_schema.sql",
            "db-migrations/claims/V2__grants_rls_money_checks_evidence_and_settlement_columns.sql",
            "db-migrations/claims/V3__registration_idempotency_key.sql");

        minioClient = MinioClient.builder()
            .endpoint(MINIO.getS3URL())
            .credentials(MINIO.getUserName(), MINIO.getPassword())
            .build();
        minioClient.makeBucket(MakeBucketArgs.builder().bucket("claim-evidence").build());
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private DocumentApi documentApi;
    @Autowired private ClaimRepository claimRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @BeforeEach
    void setTenant() {
        TenantContext.set(UUID.randomUUID());
    }

    /** A freshly REGISTERED MATURITY claim under the given tenant, claimed by the given party --
     * MATURITY is the one claim type that needs no assessment, keeping fixtures focused on
     * evidence/ownership linkage rather than re-deriving the approval workflow. Mirrors
     * {@code ClaimEvidenceIntegrationTest.registerClaim} but parameterizes the claimant so two
     * distinct parties can each own a claim under the SAME tenant. */
    private Claim registerClaim(UUID tenantId, UUID claimantPartyId) {
        Claim claim = new Claim(tenantId, "POL-EVIDENCE-DL-TEST", claimantPartyId, ClaimType.MATURITY,
            LocalDate.now().minusDays(1), new MaturityClaimDetails(LocalDate.now().minusDays(1)),
            "test-registrar", null);
        return claimRepository.save(claim);
    }

    private static SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor customerOf(UUID tenantId, UUID partyId) {
        return SecurityMockMvcRequestPostProcessors.jwt()
            .authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
            .jwt(builder -> builder.claim("tenant_id", tenantId.toString()).claim("party_id", partyId.toString()));
    }

    @Test
    void aDocumentFromAnotherClaimIsNotFoundEvenThoughTheCallerOwnsTheClaimInThePath() throws Exception {
        // Two claims, two different claimant parties, SAME tenant (so this is object-level
        // authorization being tested, not tenant isolation -- which findOrThrow already covers).
        UUID tenantId = TenantContext.get();
        UUID partyA = UUID.randomUUID();
        UUID partyB = UUID.randomUUID();
        Claim claimA = registerClaim(tenantId, partyA);
        Claim claimB = registerClaim(tenantId, partyB);

        // Upload evidence to claim B only.
        byte[] contentB = "claim-b-evidence-bytes".getBytes();
        String documentRefB = documentApi.upload("claim:" + claimB.getClaimId(), DocumentType.CLAIM_EVIDENCE,
            "uploader-b", new ByteArrayInputStream(contentB), contentB.length, "image/jpeg", "b-evidence.jpg");

        // Request claim A's URL (owned by the caller, partyA) with claim B's documentRef.
        // MUST be 404 and MUST NOT return bytes.
        mockMvc.perform(get("/claims/" + claimA.getClaimId() + "/evidence/" + documentRefB)
                .with(customerOf(tenantId, partyA)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("DOCUMENT_NOT_FOUND"));
    }

    @Test
    void downloadReturnsTheExactBytesContentTypeAndFilenameThatWereUploaded() throws Exception {
        // Round-trip through real MinIO. Asserting 200-with-non-empty-body is NOT sufficient:
        // assert byte equality, Content-Type == the uploaded media type (image/jpeg, NOT
        // application/octet-stream), and Content-Disposition carrying the original filename.
        // Task 1 exists precisely because both headers were previously unrecoverable.
        UUID tenantId = TenantContext.get();
        UUID partyId = UUID.randomUUID();
        Claim claim = registerClaim(tenantId, partyId);

        byte[] content = "real-round-trip-evidence-bytes".getBytes();
        String documentRef = documentApi.upload("claim:" + claim.getClaimId(), DocumentType.CLAIM_EVIDENCE,
            "uploader", new ByteArrayInputStream(content), content.length, "image/jpeg", "photo.jpg");

        MvcResult result = mockMvc.perform(get("/claims/" + claim.getClaimId() + "/evidence/" + documentRef)
                .with(customerOf(tenantId, partyId)))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "image/jpeg"))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.containsString("photo.jpg")))
            .andReturn();

        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(content);
    }

    @Test
    void aPreV2RowWithNullContentTypeAndFileNameStillDownloads() throws Exception {
        // Insert a document_record row with content_type/file_name NULL, simulating a document
        // uploaded before document/V2, and assert the download still succeeds with
        // application/octet-stream and the documentRef as filename -- no NPE, no malformed header.
        // This is the path EVERY document already in a real deployment will take.
        UUID tenantId = TenantContext.get();
        UUID partyId = UUID.randomUUID();
        Claim claim = registerClaim(tenantId, partyId);

        byte[] content = "pre-v2-legacy-evidence-bytes".getBytes();
        String documentRef = documentApi.upload("claim:" + claim.getClaimId(), DocumentType.CLAIM_EVIDENCE,
            "uploader", new ByteArrayInputStream(content), content.length, "image/png", "legacy.png");
        jdbcTemplate.update(
            "UPDATE document.document_record SET content_type = NULL, file_name = NULL WHERE document_ref = ?",
            documentRef);

        MvcResult result = mockMvc.perform(get("/claims/" + claim.getClaimId() + "/evidence/" + documentRef)
                .with(customerOf(tenantId, partyId)))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/octet-stream"))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.containsString(documentRef)))
            .andReturn();

        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(content);
    }

    /**
     * Final-review Finding 1. {@code MultipartFile.getContentType()} is an unvalidated client
     * header, and whatever it says is echoed back verbatim by the download endpoints -- so an
     * uploaded {@code text/html} "photo" would render as a page in the next viewer's browser
     * (stored XSS through the document store), and the published contract would be lying about the
     * media types it can return. Non-vacuous by construction: the SECOND half of this test sends a
     * byte-for-byte identical request with only the content type changed and gets 201, so the 422
     * cannot be an incidental failure of the fixture, the claim, or the multipart body.
     */
    @Test
    void uploadRejectsADisallowedContentTypeWith422AndAcceptsAnAllowedOne() throws Exception {
        UUID tenantId = TenantContext.get();
        UUID partyId = UUID.randomUUID();
        Claim claim = registerClaim(tenantId, partyId);
        byte[] bytes = "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(multipart("/claims/" + claim.getClaimId() + "/evidence")
                .file(new MockMultipartFile("file", "evil.html", "text/html", bytes))
                .with(customerOf(tenantId, partyId)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errorCode").value("CLAIM_VALIDATION_FAILED"))
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("text/html")));

        // Same claim, same caller, same bytes -- only the declared content type differs. Also
        // proves normalization: an uppercase type with a charset parameter is STORED as the bare
        // lowercase "image/jpeg", so document_record.content_type only ever holds one of the four
        // short literals the OpenAPI spec declares.
        String documentRef = com.jayway.jsonpath.JsonPath.read(
            mockMvc.perform(multipart("/claims/" + claim.getClaimId() + "/evidence")
                    .file(new MockMultipartFile("file", "photo.jpg", "IMAGE/JPEG; charset=utf-8", bytes))
                    .with(customerOf(tenantId, partyId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(),
            "$.documentRef");

        assertThat(jdbcTemplate.queryForObject(
            "SELECT content_type FROM document.document_record WHERE document_ref = ?", String.class, documentRef))
            .isEqualTo("image/jpeg");

        mockMvc.perform(get("/claims/" + claim.getClaimId() + "/evidence/" + documentRef)
                .with(customerOf(tenantId, partyId)))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "image/jpeg"));
    }

    /**
     * Final-review Finding 2. A stored {@code content_type} that is not a parseable media type
     * used to make its document PERMANENTLY undownloadable: {@code MediaType.parseMediaType} throws
     * {@code InvalidMediaTypeException extends IllegalArgumentException}, which
     * {@code GlobalExceptionHandler} maps to 400 {@code VALIDATION_ERROR} -- on a {@code GET} whose
     * path is entirely valid, so the caller is told their request is malformed forever. Finding 1's
     * allowlist stops such values entering through the evidence upload, but {@code
     * DocumentController} serves rows this module never wrote, so the fallback is still load-bearing.
     * Both degenerate stored shapes are covered: unparseable, and blank-not-null (which the
     * original {@code == null} check let through into {@code parseMediaType("")}).
     */
    @Test
    void aRowWithAMalformedOrBlankStoredContentTypeStillDownloadsAsOctetStream() throws Exception {
        UUID tenantId = TenantContext.get();
        UUID partyId = UUID.randomUUID();
        Claim claim = registerClaim(tenantId, partyId);

        byte[] content = "malformed-content-type-row-bytes".getBytes();
        String documentRef = documentApi.upload("claim:" + claim.getClaimId(), DocumentType.CLAIM_EVIDENCE,
            "uploader", new ByteArrayInputStream(content), content.length, "image/jpeg", "photo.jpg");

        // Written straight to the column, exactly as a pre-allowlist upload or a future producer
        // outside this module could have left it. "not a media type" has a space, so it is not a
        // valid type/subtype token and parseMediaType rejects it outright.
        jdbcTemplate.update(
            "UPDATE document.document_record SET content_type = 'not a media type' WHERE document_ref = ?",
            documentRef);

        MvcResult result = mockMvc.perform(get("/claims/" + claim.getClaimId() + "/evidence/" + documentRef)
                .with(customerOf(tenantId, partyId)))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/octet-stream"))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.containsString("photo.jpg")))
            .andReturn();
        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(content);

        // Empty string, not NULL: distinct from aPreV2RowWithNullContentTypeAndFileNameStillDownloads
        // above, and the case a plain null check misses. The blank file_name must fall back to the
        // documentRef too, rather than emitting `filename=""`.
        jdbcTemplate.update(
            "UPDATE document.document_record SET content_type = '', file_name = '   ' WHERE document_ref = ?",
            documentRef);

        mockMvc.perform(get("/claims/" + claim.getClaimId() + "/evidence/" + documentRef)
                .with(customerOf(tenantId, partyId)))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/octet-stream"))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.containsString(documentRef)));
    }

    // The test formerly here, `anAgentMayDownloadEvidenceForAClaimBelongingToADifferentParty`,
    // asserted the OLD pre-agent-scoping contract (docs/04-api-contracts.md's now-superseded
    // "unrestricted in-tenant read, same as staff" for agents) -- exactly the gap
    // AgentBookOfBusinessScopingTest's `enforceAgentOwnClaimOnly` scoping was built to close. It
    // also never had `distribution`/`policy` schema migrations applied, so once that scoping
    // landed it failed with a 500 (missing table), not the 403 the new contract actually requires.
    // Real positive/negative coverage for this endpoint's agent scoping now lives in
    // AgentBookOfBusinessScopingTest (`downloadEvidenceAsAgentReturns200ForAClaimAgainstAPolicyInItsOwnDownline`
    // / `downloadEvidenceAsAgentReturns403ForAClaimOutsideItsTeam`), which already has the full
    // migration set and fixture helpers this scenario needs.
}
