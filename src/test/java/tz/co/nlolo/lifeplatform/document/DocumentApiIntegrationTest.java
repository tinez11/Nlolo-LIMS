package tz.co.nlolo.lifeplatform.document;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.document.api.DocumentApi;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(classes = Application.class)
class DocumentApiIntegrationTest {

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
    static void applyMigrationAndCreateBuckets() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/document/V1__create_document_schema.sql",
            "db-migrations/document/V2__add_content_type_and_file_name.sql");

        minioClient = MinioClient.builder()
            .endpoint(MINIO.getS3URL())
            .credentials(MINIO.getUserName(), MINIO.getPassword())
            .build();
        minioClient.makeBucket(MakeBucketArgs.builder().bucket("policy-documents").build());
        minioClient.makeBucket(MakeBucketArgs.builder().bucket("kyc-evidence").build());
        minioClient.makeBucket(MakeBucketArgs.builder().bucket("underwriting-evidence").build());
    }

    @Autowired
    private DocumentApi documentApi;

    @BeforeEach
    void setTenant() {
        TenantContext.set(UUID.randomUUID());
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void uploadAndDownloadRoundTrips() {
        byte[] originalContent = "kyc-scan-bytes".getBytes();

        String documentRef = documentApi.upload("party:test-party-id", DocumentType.KYC_EVIDENCE, "test-uploader",
            new ByteArrayInputStream(originalContent), originalContent.length, "application/octet-stream", "scan.jpg");

        byte[] downloaded = documentApi.download(documentRef);
        assertThat(downloaded).isEqualTo(originalContent);

        var metadata = documentApi.getMetadata(documentRef);
        assertThat(metadata.ownerContext()).isEqualTo("party:test-party-id");
        assertThat(metadata.documentType()).isEqualTo(DocumentType.KYC_EVIDENCE);
    }

    @Test
    void uploadAndDownloadRoundTripsForUnderwritingEvidence() throws Exception {
        byte[] originalContent = "underwriting-evidence-bytes".getBytes();

        String documentRef = documentApi.upload("underwriting-case:test-case-id", DocumentType.UNDERWRITING_EVIDENCE,
            "test-uploader", new ByteArrayInputStream(originalContent), originalContent.length, "application/octet-stream",
            "underwriting-evidence.pdf");

        byte[] downloaded = documentApi.download(documentRef);
        assertThat(downloaded).isEqualTo(originalContent);

        var metadata = documentApi.getMetadata(documentRef);
        assertThat(metadata.ownerContext()).isEqualTo("underwriting-case:test-case-id");
        assertThat(metadata.documentType()).isEqualTo(DocumentType.UNDERWRITING_EVIDENCE);

        // Confirms MinioDocumentStorage.bucketFor() actually routed this object into the
        // dedicated underwriting-evidence bucket, not the general policy-documents bucket.
        // Object key mirrors DocumentApiImpl.storageKey(): "<tenantId>/<documentRef>".
        assertThat(minioClient.statObject(StatObjectArgs.builder()
            .bucket("underwriting-evidence")
            .object(TenantContext.get() + "/" + documentRef)
            .build())).isNotNull();
    }
}
