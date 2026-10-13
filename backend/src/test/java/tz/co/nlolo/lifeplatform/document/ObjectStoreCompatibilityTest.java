package tz.co.nlolo.lifeplatform.document;

import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The object store the platform's documents live in speaks enough S3 for the client that talks to it (2026-10-13).
 *
 * <p><b>Why this test exists.</b> MinIO withdrew public distribution -- its Docker Hub repositories are gone,
 * {@code dl.min.io} answers 410, and quay.io demands credentials -- so no new environment can pull the image the dev
 * stack still runs from a local cache. The platform's own use of it is two calls, {@code putObject} and
 * {@code getObject} through the {@code io.minio} SDK, which is an S3 client and not MinIO-specific; so the store can
 * be replaced without touching {@link tz.co.nlolo.lifeplatform.document.infrastructure.MinioDocumentStorage}. This
 * asserts that against SeaweedFS, the replacement, rather than trusting the word "S3-compatible" on a project page.
 *
 * <p>It deliberately exercises the SDK the application uses, not an HTTP call of its own: what matters is that the
 * signing, the bucket semantics and the streaming upload the real code path depends on all work.
 */
@Testcontainers
class ObjectStoreCompatibilityTest {

    private static final String ACCESS_KEY = "platform-docs";
    private static final String SECRET_KEY = "compatibility-check-secret";

    @Container
    static GenericContainer<?> SEAWEED = new GenericContainer<>("chrislusf/seaweedfs:3.80")
        .withCopyToContainer(MountableFile.forClasspathResource("object-store/s3-identities.json"),
            "/etc/seaweedfs/s3.json")
        .withCommand("server", "-s3", "-s3.config=/etc/seaweedfs/s3.json", "-dir=/data")
        .withExposedPorts(8333)
        .waitingFor(Wait.forListeningPorts(8333).withStartupTimeout(java.time.Duration.ofMinutes(3)));

    private static MinioClient client() {
        return MinioClient.builder()
            .endpoint("http://" + SEAWEED.getHost() + ":" + SEAWEED.getMappedPort(8333))
            .credentials(ACCESS_KEY, SECRET_KEY)
            .build();
    }

    @Test
    void aDocumentPutIntoABucketComesBackByteForByte() throws Exception {
        MinioClient minio = client();
        // The four buckets the platform pre-creates; one is enough to prove the semantics.
        minio.makeBucket(MakeBucketArgs.builder().bucket("kyc-evidence").build());

        byte[] evidence = "scanned national id, page 1".getBytes(StandardCharsets.UTF_8);
        minio.putObject(PutObjectArgs.builder()
            .bucket("kyc-evidence")
            .object("party/7ddd05c3/national-id.jpg")
            .stream(new ByteArrayInputStream(evidence), evidence.length, -1)
            .contentType("image/jpeg")
            .build());

        try (var in = minio.getObject(GetObjectArgs.builder()
                .bucket("kyc-evidence").object("party/7ddd05c3/national-id.jpg").build())) {
            assertThat(in.readAllBytes()).isEqualTo(evidence);
        }
    }

    @Test
    void theWrongSecretIsRefused() {
        MinioClient wrong = MinioClient.builder()
            .endpoint("http://" + SEAWEED.getHost() + ":" + SEAWEED.getMappedPort(8333))
            .credentials(ACCESS_KEY, "not-the-secret")
            .build();

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
            wrong.makeBucket(MakeBucketArgs.builder().bucket("should-not-appear").build())))
            .isNotNull();
    }
}
