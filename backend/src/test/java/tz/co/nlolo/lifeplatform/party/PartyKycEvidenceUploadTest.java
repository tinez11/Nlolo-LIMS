package tz.co.nlolo.lifeplatform.party;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import com.jayway.jsonpath.JsonPath;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * `POST /parties/{partyId}/kyc-evidence` -- the real upload path `PartyApi.submitKycEvidence` has
 * always needed (a real {@code evidenceDocumentRef}) but that, until this staff-portal CRUD audit,
 * had no way to produce for a KYC purpose anywhere on the platform. Container setup, migration
 * list, and MinIO bucket idiom copied from {@code claims.ClaimEvidenceDownloadTest} -- the same
 * shape, a different owning aggregate.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class PartyKycEvidenceUploadTest {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Container
    static MinIOContainer MINIO = new MinIOContainer("minio/minio:latest");

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
    static void applyMigrationsAndCreateBucket() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/document/V1__create_document_schema.sql",
            "db-migrations/document/V2__add_content_type_and_file_name.sql",
            "db-migrations/document/V7__journal_support_document_type.sql",
            "db-migrations/document/V8__reinsurance_statement_document_type.sql",
            "db-migrations/party/V1__create_party_schema.sql",
            "db-migrations/party/V2__individual_person_record.sql",
            "db-migrations/party/V4__registered_by_agent.sql",
            "db-migrations/party/V5__registered_by_name.sql",
            "db-migrations/party/V6__client_reference.sql",
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql");

        MinioClient minioClient = MinioClient.builder()
            .endpoint(MINIO.getS3URL())
            .credentials(MINIO.getUserName(), MINIO.getPassword())
            .build();
        minioClient.makeBucket(MakeBucketArgs.builder().bucket("kyc-evidence").build());
    }

    @Autowired private MockMvc mockMvc;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor staffOf(UUID tenantId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
            .jwt(builder -> builder.claim("tenant_id", tenantId.toString()));
    }

    /** The subject that registers every fixture client here, and therefore owns it. */
    private static final String REGISTERING_AGENT_SUBJECT = "registering-agent";

    private UUID registerIndividual(UUID tenantId) throws Exception {
        String response = mockMvc.perform(post("/parties/individuals")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.subject(REGISTERING_AGENT_SUBJECT)
                        .claim("tenant_id", tenantId.toString())))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("""
                    {"fullName":"Kyc Evidence Upload Test","dateOfBirth":"1990-05-12",
                     "sex":"FEMALE","idType":"NATIONAL_ID","idNumber":"%s",
                     "contactInfo":{"phoneNumber":"+255712345699"}}
                    """.formatted("19900512-" + UUID.randomUUID().toString().substring(0, 5) + "-00001-11")))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.partyId"));
    }

    @Test
    void uploadKycEvidenceReturnsARealDocumentRef() throws Exception {
        // No OpenApi matcher on this multipart request itself -- swagger-request-validator's
        // MockMvc integration cannot read a multipart request body at all ("A request body is
        // required but none found", empirically confirmed), a pre-existing platform limitation
        // (ClaimsContractTest's own multipart evidence-upload test never pairs the matcher with
        // the multipart call either, only with a later plain GET).
        UUID tenantId = UUID.randomUUID();
        UUID partyId = registerIndividual(tenantId);

        mockMvc.perform(multipart("/parties/" + partyId + "/kyc-evidence")
                .file(new MockMultipartFile("file", "id-scan.jpg", "image/jpeg", "id-scan-bytes".getBytes()))
                .with(staffOf(tenantId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.documentRef").isNotEmpty());
    }

    /**
     * An agent MAY file evidence, for a client they registered.
     *
     * <p>This test asserted a flat 403 for any agents-realm caller until 2026-09-30. The agent
     * takes the documents at the point of sale, so requiring them to reach a staff member
     * before anything could be filed made the staff member a courier. What the agent still may
     * not do is decide: {@code POST /parties/{id}/kyc} stays staff-only, which is the same
     * separation underwriting and claims insist on.
     *
     * <p>{@code registerIndividual} above registers as an agents-realm caller, so the token
     * here is the one that owns the client.
     */
    @Test
    void uploadKycEvidenceAdmitsTheAgentWhoRegisteredTheClient() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID partyId = registerIndividual(tenantId);

        mockMvc.perform(multipart("/parties/" + partyId + "/kyc-evidence")
                .file(new MockMultipartFile("file", "id-scan.jpg", "image/jpeg", "id-scan-bytes".getBytes()))
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.subject(REGISTERING_AGENT_SUBJECT)
                        .claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.documentRef").isNotEmpty());
    }

    @Test
    void uploadKycEvidenceRefusesAnAgentFilingAgainstSomebodyElsesClient() throws Exception {
        // The narrower half of the same rule, and the one that makes opening the endpoint safe:
        // an agent may file documents against their own client and nobody else's. Without this
        // the permission above would let any agent write to any party in the tenant.
        UUID tenantId = UUID.randomUUID();
        UUID partyId = registerIndividual(tenantId);

        mockMvc.perform(multipart("/parties/" + partyId + "/kyc-evidence")
                .file(new MockMultipartFile("file", "id-scan.jpg", "image/jpeg", "id-scan-bytes".getBytes()))
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
                    .jwt(builder -> builder.subject("a-different-agent")
                        .claim("tenant_id", tenantId.toString()))))
            .andExpect(status().isForbidden());
    }

    @Test
    void uploadKycEvidenceRejectsADisallowedContentTypeWith400() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID partyId = registerIndividual(tenantId);

        mockMvc.perform(multipart("/parties/" + partyId + "/kyc-evidence")
                .file(new MockMultipartFile("file", "malicious.html", "text/html", "<script>evil</script>".getBytes()))
                .with(staffOf(tenantId)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void theUploadedEvidenceRefCanBeUsedToSubmitARealKycDecision() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID partyId = registerIndividual(tenantId);

        String uploadResponse = mockMvc.perform(multipart("/parties/" + partyId + "/kyc-evidence")
                .file(new MockMultipartFile("file", "id-scan.jpg", "image/jpeg", "id-scan-bytes".getBytes()))
                .with(staffOf(tenantId)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String documentRef = JsonPath.read(uploadResponse, "$.documentRef");

        mockMvc.perform(post("/parties/" + partyId + "/kyc")
                .with(staffOf(tenantId))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("""
                    {"status":"VERIFIED","evidenceDocumentRef":"%s"}
                    """.formatted(documentRef)))
            .andExpect(status().isOk());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/parties/" + partyId)
                .with(staffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.kycStatus").value("VERIFIED"));
    }
}
