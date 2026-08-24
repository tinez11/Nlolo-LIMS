package tz.co.nlolo.lifeplatform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the platform's metrics-exposure posture, which is a security boundary rather than a
 * convenience setting.
 *
 * <p><b>Scope note, corrected after review.</b> This class guards the TRANSPORT: that metrics are
 * exported, reachable where Prometheus scrapes, and unreachable where they should not be. It says
 * nothing about whether a given alert rule has a producer, and fixing the transport did NOT make
 * all 13 rules live -- only the 7 whose metrics this application registers. The other 6 reference
 * metrics nothing emits and remain silent; {@link AlertRuleMetricProducerTest} owns that half and
 * enumerates both sets. Read the two together before concluding "alerting works".
 *
 * <p><b>Why this test exists.</b> M6's final whole-branch review found that NO
 * {@code observability/alert-rules.yml} rule could fire -- all 13 of them, across every milestone
 * back to M4 -- because {@code micrometer-registry-prometheus} was absent from the classpath and
 * {@code management.endpoints.web.exposure.include} listed only {@code health,info}, while
 * {@code observability/prometheus.yml} scraped {@code /actuator/prometheus} (a 404). Every metric
 * name was exact and every rule expression was correct; the metrics simply were not exported. That
 * is the "vacuous verification" class this project keeps rediscovering one level deeper: an alert
 * rule referencing a metric nothing publishes is as silent as no rule at all. This test is the
 * regression guard, and it asserts on REAL SCRAPE CONTENT rather than on a 200, because an
 * endpoint that answers but omits the counters would satisfy the weaker check.
 *
 * <p><b>Why {@code @AutoConfigureObservability} is mandatory here, and why its absence would make
 * this whole class vacuous.</b> Spring Boot deliberately disables metrics EXPORT inside
 * {@code @SpringBootTest} — {@code PrometheusMetricsExportAutoConfiguration} backs off with
 * "{@code management.defaults.metrics.export.enabled is considered false}" (confirmed here from
 * Boot's own condition-evaluation report), leaving a bare {@code SimpleMeterRegistry} and no
 * {@code /actuator/prometheus} at all. Without this annotation the scrape assertions below fail
 * against a correctly-configured production app, and — far worse — a future author "fixing" that
 * failure by relaxing them would produce a test that passes while proving nothing about the
 * deployed configuration. The annotation opts this one class back into the real production
 * behaviour so the assertions describe what actually ships.
 *
 * <p><b>Why a separate management port.</b> See {@code application.yml}'s own comment. In short:
 * {@code infra/docker-compose.yml} publishes {@code 8080:8080} to the host; Prometheus scrapes
 * with no credentials; this app authenticates with Keycloak JWTs a scraper cannot obtain. Binding
 * actuator to 9090 and not publishing 9090 keeps metrics reachable inside the Docker network and
 * absent from the host. The first two tests below are what make that claim falsifiable.
 */
@Testcontainers
@AutoConfigureObservability
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
class ActuatorExposureTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** Endpoints that must never be reachable on EITHER port with the current allow-list.
     * {@code env} would disclose SPRING_DATASOURCE_PASSWORD and every Keycloak/MinIO credential;
     * {@code heapdump} would disclose live JWTs and decrypted row data; {@code loggers} is
     * remotely writable; {@code beans}/{@code configprops} map the whole application. */
    private static final List<String> MUST_NOT_BE_EXPOSED =
        List.of("env", "heapdump", "loggers", "beans", "configprops", "threaddump", "mappings");

    @LocalServerPort
    private int serverPort;

    @LocalManagementPort
    private int managementPort;

    private final TestRestTemplate rest = new TestRestTemplate();

    /** The management port must genuinely be a DIFFERENT port -- if this ever collapses back to
     * one port, every other assertion in this class silently stops meaning anything. */
    @Test
    void managementRunsOnItsOwnPortSeparateFromTheApplicationPort() {
        assertThat(managementPort)
            .as("actuator must bind its own port; a shared port would put metrics on the "
                + "host-published application port")
            .isNotEqualTo(serverPort);
    }

    @Test
    void prometheusScrapeIsAbsentFromTheHostPublishedApplicationPort() {
        ResponseEntity<String> response = rest.getForEntity(
            "http://localhost:" + serverPort + "/actuator/prometheus", String.class);

        // Not 200 is the property under test. Whether it presents as 404 (no such endpoint on this
        // port) or 401 (SecurityConfig's anyRequest().authenticated() catching it first) is a
        // framework detail; either way a host-side scraper gets no metrics.
        assertThat(response.getStatusCode().value())
            .as("metrics must not be served on the port docker-compose publishes to the host")
            .isNotEqualTo(200);
    }

    @Test
    void prometheusScrapeOnTheManagementPortCarriesTheRealPlatformCounters() {
        ResponseEntity<String> response = rest.getForEntity(
            "http://localhost:" + managementPort + "/actuator/prometheus", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String body = response.getBody();
        assertThat(body).isNotNull();

        // A counter is only registered with the Prometheus registry once its first increment
        // happens, so a freshly-booted app legitimately does not list every lifeplatform_* series
        // yet. What must hold is that the registry is genuinely wired and scraping real data --
        // asserted here on JVM/process series Micrometer always publishes. The named-counter
        // assertion lives in prometheusScrapeExposesACounterOnceItHasBeenIncremented below, which
        // increments one first.
        assertThat(body)
            .as("a real Prometheus exposition, not an empty or error body")
            .contains("jvm_memory_used_bytes")
            .contains("# TYPE");
    }

    /** The assertion that would have caught M6's finding: a counter this platform actually defines
     * must appear in a real scrape once incremented. Uses the app's OWN MeterRegistry bean, so a
     * registry that was wired but not exported would fail here. */
    @Test
    void prometheusScrapeExposesACounterOnceItHasBeenIncremented(
            @Autowired io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        meterRegistry.counter("lifeplatform_payment_in_doubt_total").increment();

        ResponseEntity<String> response = rest.getForEntity(
            "http://localhost:" + managementPort + "/actuator/prometheus", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody())
            .as("an incremented lifeplatform_* counter must reach the scrape, or its alert rule "
                + "in observability/alert-rules.yml can never fire")
            .contains("lifeplatform_payment_in_doubt_total");
    }

    /** The probe paths application.yml's deployment-impact comment tells operators to use. All
     * three must actually answer on the management port -- a review of this branch found the two
     * GROUP paths returned 401, because SecurityConfig's "/actuator/health" literal does not match
     * them, so the guidance was wrong as written. This pins guidance and code together. */
    @Test
    void allThreeDocumentedProbePathsAnswerOnTheManagementPort() {
        for (String probe : List.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness")) {
            assertThat(rest.getForEntity("http://localhost:" + managementPort + probe, String.class)
                    .getStatusCode().value())
                .as("%s is documented as a probe path in application.yml and must be reachable "
                    + "on the management port", probe)
                .isEqualTo(200);
        }
    }

    /** Permitting the two health GROUP paths must not have opened per-component health paths,
     * which is why SecurityConfig lists exact literals rather than /actuator/health/**. */
    @Test
    void perComponentHealthPathsRemainUnreachable() {
        for (String component : List.of("db", "diskSpace", "ping", "minio", "redis")) {
            assertThat(rest.getForEntity(
                    "http://localhost:" + managementPort + "/actuator/health/" + component, String.class)
                    .getStatusCode().value())
                .as("/actuator/health/%s must not be reachable -- only the two documented group "
                    + "paths are permitted", component)
                .isNotEqualTo(200);
        }
    }

    @Test
    void sensitiveActuatorEndpointsAreExposedOnNeitherPort() {
        for (String endpoint : MUST_NOT_BE_EXPOSED) {
            assertThat(rest.getForEntity(
                    "http://localhost:" + managementPort + "/actuator/" + endpoint, String.class)
                    .getStatusCode().value())
                .as("/actuator/%s must not be exposed on the management port -- see the "
                    + "allow-list comment in application.yml", endpoint)
                .isNotEqualTo(200);

            assertThat(rest.getForEntity(
                    "http://localhost:" + serverPort + "/actuator/" + endpoint, String.class)
                    .getStatusCode().value())
                .as("/actuator/%s must not be exposed on the application port", endpoint)
                .isNotEqualTo(200);
        }
    }
}
