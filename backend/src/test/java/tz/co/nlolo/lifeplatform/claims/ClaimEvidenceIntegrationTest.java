package tz.co.nlolo.lifeplatform.claims;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.claims.api.ClaimEvidenceView;
import tz.co.nlolo.lifeplatform.claims.api.ClaimStatus;
import tz.co.nlolo.lifeplatform.claims.api.ClaimType;
import tz.co.nlolo.lifeplatform.claims.api.ClaimsApi;
import tz.co.nlolo.lifeplatform.claims.api.InvalidClaimStateException;
import tz.co.nlolo.lifeplatform.claims.api.MaturityClaimDetails;
import tz.co.nlolo.lifeplatform.claims.domain.Claim;
import tz.co.nlolo.lifeplatform.claims.infrastructure.ClaimRepository;
import tz.co.nlolo.lifeplatform.document.api.DocumentApi;
import tz.co.nlolo.lifeplatform.document.api.DocumentNotFoundException;
import tz.co.nlolo.lifeplatform.document.api.DocumentType;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Task 8: {@code attachEvidence}/{@code listEvidence} against REAL MinIO -- the direct evidence
 * for the roadmap's "document evidence upload/retrieval tested against MinIO" acceptance
 * criterion. Follows {@code document.DocumentApiIntegrationTest}'s exact container/bucket setup
 * verbatim (a Postgres superuser connection + a MinIOContainer with buckets created manually in
 * {@code @BeforeAll}, since the container never runs infra/docker-compose.yml's minio-init job).
 */
@Testcontainers
@SpringBootTest(classes = Application.class)
class ClaimEvidenceIntegrationTest {

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
            "db-migrations/document/V7__journal_support_document_type.sql",
            "db-migrations/claims/V1__create_claims_schema.sql",
            "db-migrations/claims/V2__grants_rls_money_checks_evidence_and_settlement_columns.sql",
            "db-migrations/claims/V3__registration_idempotency_key.sql",
            "db-migrations/claims/V5__claim_policy_member.sql",
            "db-migrations/claims/V6__exclusion_decline.sql",
            "db-migrations/claims/V7__claim_assessment_assessor_name.sql",
            "db-migrations/claims/V8__claim_evidence_uploaded_by_name.sql");

        minioClient = MinioClient.builder()
            .endpoint(MINIO.getS3URL())
            .credentials(MINIO.getUserName(), MINIO.getPassword())
            .build();
        minioClient.makeBucket(MakeBucketArgs.builder().bucket("policy-documents").build());
        minioClient.makeBucket(MakeBucketArgs.builder().bucket("kyc-evidence").build());
        minioClient.makeBucket(MakeBucketArgs.builder().bucket("underwriting-evidence").build());
        minioClient.makeBucket(MakeBucketArgs.builder().bucket("claim-evidence").build());
    }

    @Autowired private ClaimsApi claimsApi;
    @Autowired private DocumentApi documentApi;
    @Autowired private ClaimRepository claimRepository;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @BeforeEach
    void setTenant() {
        TenantContext.set(UUID.randomUUID());
    }

    /** A freshly REGISTERED MATURITY claim under the currently-set tenant -- MATURITY is the one
     * claim type that needs no assessment to progress, which keeps this test's fixtures focused
     * on evidence linkage rather than re-deriving the whole approval workflow. */
    private Claim registerClaim(UUID tenantId) {
        Claim claim = new Claim(tenantId, "POL-EVIDENCE-TEST", null, UUID.randomUUID(), ClaimType.MATURITY,
            LocalDate.now().minusDays(1), new MaturityClaimDetails(LocalDate.now().minusDays(1)), "test-registrar", null);
        return claimRepository.save(claim);
    }

    @Test
    void attachEvidenceUploadsToTheDedicatedClaimEvidenceBucketAndLinksIt() throws Exception {
        UUID tenantId = TenantContext.get();
        Claim claim = registerClaim(tenantId);

        byte[] originalContent = "claim-evidence-bytes".getBytes();
        String documentRef = documentApi.upload("claim:" + claim.getClaimId(), DocumentType.CLAIM_EVIDENCE,
            "test-uploader", new ByteArrayInputStream(originalContent), originalContent.length,
            "application/octet-stream", "evidence.jpg");

        // Falsifies Step 1: confirms MinioDocumentStorage.bucketFor() actually routed this object
        // into the dedicated claim-evidence bucket, not the general policy-documents bucket.
        // Object key mirrors DocumentApiImpl.storageKey(): "<tenantId>/<documentRef>".
        assertThat(minioClient.statObject(StatObjectArgs.builder()
            .bucket("claim-evidence")
            .object(tenantId + "/" + documentRef)
            .build())).isNotNull();

        ClaimEvidenceView attached = claimsApi.attachEvidence(claim.getClaimId(), documentRef,
            "Maturity certificate scan", "test-uploader", null);

        assertThat(attached.claimId()).isEqualTo(claim.getClaimId());
        assertThat(attached.documentRef()).isEqualTo(documentRef);
        assertThat(attached.description()).isEqualTo("Maturity certificate scan");
        assertThat(attached.uploadedBy()).isEqualTo("test-uploader");

        List<ClaimEvidenceView> evidence = claimsApi.listEvidence(claim.getClaimId());
        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).documentRef()).isEqualTo(documentRef);

        // download() returns byte-identical content through the same claim-evidence bucket.
        byte[] downloaded = documentApi.download(documentRef);
        assertThat(downloaded).isEqualTo(originalContent);
    }

    @Test
    void attachEvidenceRejectsADocumentRefBelongingToAnotherTenant() {
        UUID otherTenantId = UUID.randomUUID();
        TenantContext.set(otherTenantId);
        byte[] content = "other-tenant-bytes".getBytes();
        String foreignDocumentRef = documentApi.upload("claim:foreign", DocumentType.CLAIM_EVIDENCE,
            "other-uploader", new ByteArrayInputStream(content), content.length, "application/octet-stream",
            "foreign-evidence.jpg");

        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        Claim claim = registerClaim(tenantId);

        assertThrows(DocumentNotFoundException.class,
            () -> claimsApi.attachEvidence(claim.getClaimId(), foreignDocumentRef, "desc", "test-uploader", null));
    }

    @Test
    void attachEvidenceRejectsASettledClaim() {
        UUID tenantId = TenantContext.get();
        Claim claim = registerClaim(tenantId);

        // MATURITY auto-approves REGISTERED -> APPROVED -> SETTLEMENT_REQUESTED -> SETTLED,
        // with no assessment required (Claim.approve's own javadoc).
        claim.approve(BigDecimal.valueOf(1000), "TZS", null);
        claim.markSettlementRequested("idem-key-" + claim.getClaimId());
        claim.markSettled();
        claimRepository.save(claim);
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.SETTLED);

        byte[] content = "late-evidence-bytes".getBytes();
        String documentRef = documentApi.upload("claim:" + claim.getClaimId(), DocumentType.CLAIM_EVIDENCE,
            "test-uploader", new ByteArrayInputStream(content), content.length, "application/octet-stream",
            "late-evidence.jpg");

        assertThrows(InvalidClaimStateException.class,
            () -> claimsApi.attachEvidence(claim.getClaimId(), documentRef, "too late", "test-uploader", null));
    }
}
