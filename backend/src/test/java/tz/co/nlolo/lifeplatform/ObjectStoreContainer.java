package tz.co.nlolo.lifeplatform;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;

/**
 * The S3 object store the tests run documents against (2026-10-13).
 *
 * <p>A drop-in replacement for Testcontainers' {@code MinIOContainer}, down to the three accessors the tests call, so
 * the switch costs each of them an import and a constructor. It exists because <b>MinIO withdrew public
 * distribution</b>: its Docker Hub repositories are gone, {@code dl.min.io} answers 410 and quay.io demands
 * credentials, so {@code new MinIOContainer("minio/minio:latest")} now works only on a machine that happens to hold
 * the image in its local cache -- this one does; CI and any new machine do not, and the failure would read as a
 * network error rather than as what it is.
 *
 * <p>SeaweedFS speaks the S3 API the {@code io.minio} SDK talks, which is all the platform needs of it: see
 * {@link tz.co.nlolo.lifeplatform.document.ObjectStoreCompatibilityTest}, which proves put, get and credential
 * rejection against this very image rather than taking "S3-compatible" on trust.
 */
public class ObjectStoreContainer extends GenericContainer<ObjectStoreContainer> {

    /** Matches src/test/resources/object-store/s3-identities.json, and deploy/server/.env.example's defaults. */
    private static final String ACCESS_KEY = "platform-docs";
    private static final String SECRET_KEY = "compatibility-check-secret";
    private static final int S3_PORT = 8333;

    public ObjectStoreContainer() {
        super("chrislusf/seaweedfs:3.80");
        withCopyToContainer(MountableFile.forClasspathResource("object-store/s3-identities.json"),
            "/etc/seaweedfs/s3.json");
        withCommand("server", "-s3", "-s3.config=/etc/seaweedfs/s3.json", "-dir=/data");
        withExposedPorts(S3_PORT);
        // The master, volume server and filer all start before the S3 gateway does; the port is the honest signal.
        waitingFor(Wait.forListeningPorts(S3_PORT).withStartupTimeout(Duration.ofMinutes(3)));
    }

    public String getS3URL() {
        return "http://" + getHost() + ":" + getMappedPort(S3_PORT);
    }

    public String getUserName() {
        return ACCESS_KEY;
    }

    public String getPassword() {
        return SECRET_KEY;
    }
}
