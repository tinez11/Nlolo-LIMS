package tz.co.nlolo.lifeplatform.payment;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 8's webhook (POST /webhooks/mobile-money-callback) has no committed test coverage of its
 * own anywhere else -- Task 9's contract tests exercise the OTHER three (read) endpoints, and
 * every empirical check performed while fixing the review's Critical/Important findings so far
 * used a throwaway test class deleted before commit. That left nothing to catch a regression:
 * {@code MobileMoneyCallbackController} swallows every exception and always acks 200, so a
 * regression in the tenant-resolution/transaction-timing/ambiguity-detection logic would be
 * SILENT -- 200 returned, nothing mutated, green build, no alarm. This class is the permanent
 * guard against that.
 *
 * <p>Follows {@code AppRolePrivilegesIntegrationTest}'s pattern exactly: the app's OWN Spring
 * {@code DataSource} is pointed at a real {@code app_role} login (NOSUPERUSER NOBYPASSRLS, the
 * same identity every real deployment runs as), not the Testcontainers superuser every other
 * integration test in this suite uses -- RLS is genuinely enforced for every query this test
 * exercises, including the ones inside the webhook's own request handling. Runs a real embedded
 * HTTP server ({@code RANDOM_PORT}) so the ACTUAL filter chain (HMAC verification, permitAll
 * routing) is exercised end-to-end, not simulated.
 */
@Testcontainers
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MobileMoneyCallbackIntegrationTest {

    private static final String APP_ROLE_PASSWORD = "callback_it_password";
    private static final String HMAC_SECRET = "dev-only-placeholder-secret"; // application.yml default
    private static final String AMBIGUOUS_COUNTER = "lifeplatform_payment_callback_ambiguous_total";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "app_role");
        registry.add("spring.datasource.password", () -> APP_ROLE_PASSWORD);
    }

    @BeforeAll
    static void applyMigrationsAndBootstrapAppRole() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/payment/V1__create_payment_schema.sql",
            "db-migrations/payment/V2__grants_rls_money_checks_version_and_tenant_scoped_registries.sql",
            "db-migrations/payment/V3__inbound_callback_tenant_resolver.sql");
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER ROLE app_role LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '"
                + APP_ROLE_PASSWORD + "'");
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MeterRegistry meterRegistry;

    private final TestRestTemplate rest = new TestRestTemplate();

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    /** Pins Criticals 1-3 together: the request must actually reach the controller (Critical 1 --
     * SecurityConfig's permitAll()/addFilterBefore wiring), the controller must actually be able
     * to read the body after the filter's own read (Critical 2 -- body replay), and the mutation
     * must land under the correct tenant's real RLS-scoped connection (Critical 3 -- the
     * transaction must open strictly after TenantContext is resolved). */
    @Test
    void correctlySignedCallbackCompletesTheRightDisbursementUnderRealAppRoleRls() throws Exception {
        UUID tenantId = UUID.randomUUID();
        insertDisbursement(tenantId, "it-key-happy", "GW-HAPPY-PATH");

        String body = callbackBody("SUCCESS", "ref-1", "GW-HAPPY-PATH", null);
        ResponseEntity<String> response = postSigned(body);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var row = readDisbursement(tenantId, "GW-HAPPY-PATH");
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("gatewayReference")).isEqualTo("GW-HAPPY-PATH");
    }

    /** Pins Important 2: a FAILED outcome must not erase gateway_reference -- if it did, a
     * REDELIVERED failure callback for the same reference could never resolve its tenant again
     * (resolve_disbursement_tenant keys on this column), silently stranding it. Verifies both the
     * first delivery and a simulated redelivery of the identical callback. */
    @Test
    void failedCallbackPreservesGatewayReferenceSoARedeliveredFailureCanStillResolveItsTenant() throws Exception {
        UUID tenantId = UUID.randomUUID();
        insertDisbursement(tenantId, "it-key-fail", "GW-FAIL-PATH");

        String body = callbackBody("FAILED", "ref-2", "GW-FAIL-PATH", "INSUFFICIENT_FLOAT");

        ResponseEntity<String> first = postSigned(body);
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        var afterFirst = readDisbursement(tenantId, "GW-FAIL-PATH");
        assertThat(afterFirst.get("status")).isEqualTo("FAILED");
        assertThat(afterFirst.get("gatewayReference")).isEqualTo("GW-FAIL-PATH");

        // Redelivery of the exact same callback -- an at-least-once gateway's normal behavior.
        // markFailed's own idempotent short-circuit must be reachable at all, which requires
        // resolve_disbursement_tenant to still find this row by gateway_reference.
        ResponseEntity<String> redelivered = postSigned(body);
        assertThat(redelivered.getStatusCode().value()).isEqualTo(200);
        var afterRedelivery = readDisbursement(tenantId, "GW-FAIL-PATH");
        assertThat(afterRedelivery.get("status")).isEqualTo("FAILED");
        assertThat(afterRedelivery.get("gatewayReference")).isEqualTo("GW-FAIL-PATH");
    }

    /** Pins Important 3 and Critical 5 together: two DIFFERENT tenants sharing one
     * gateway_reference (the migration's own comment calls this a legitimate, expected case)
     * must resolve to NO tenant, not an arbitrary one, so a callback against it leaves BOTH rows
     * untouched -- and the AMBIGUOUS outcome must be distinguishable from an ordinary NOT_FOUND,
     * via a real, observable metric increment. */
    @Test
    void ambiguousGatewayReferenceAcrossTenantsIsNotAppliedToEitherAndIncrementsAmbiguousMetric() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        insertDisbursement(tenantA, "it-key-amb-a", "GW-SHARED");
        insertDisbursement(tenantB, "it-key-amb-b", "GW-SHARED");

        double before = ambiguousCounterValue();

        String body = callbackBody("SUCCESS", "ref-amb", "GW-SHARED", null);
        ResponseEntity<String> response = postSigned(body);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(readDisbursement(tenantA, "GW-SHARED").get("status")).isEqualTo("PENDING");
        assertThat(readDisbursement(tenantB, "GW-SHARED").get("status")).isEqualTo("PENDING");
        assertThat(ambiguousCounterValue()).isEqualTo(before + 1);
    }

    /** Pins I6: SecurityConfig's permitAll() rule and MobileMoneyHmacFilter's own CALLBACK_PATH
     * constant must stay pointed at the exact same path -- an unsigned request to that exact path
     * must be rejected 401 by the filter, never silently pass through as if the exemption and the
     * filter had drifted apart. */
    @Test
    void unsignedRequestToTheExactPermittedPathIsRejected() {
        String body = callbackBody("SUCCESS", "ref-unsigned", "GW-UNSIGNED", null);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.exchange("http://localhost:" + port + "/webhooks/mobile-money-callback",
            HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    /** Regression guard for Critical 4: a matrix parameter on the callback path must not narrow
     * the filter's own applicability check below what Spring MVC/Security actually route. */
    @Test
    void matrixParameterOnTheCallbackPathCannotBypassHmacVerification() {
        String body = callbackBody("SUCCESS", "ref-bypass", "GW-BYPASS-ATTEMPT", null);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.exchange(
            "http://localhost:" + port + "/webhooks/mobile-money-callback;x=1",
            HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    /** Negative control: a wrong signature must still 401, proving the filter's verification
     * genuinely executes rather than the wiring merely passing everything through. */
    @Test
    void wrongSignatureIsRejected() {
        String body = callbackBody("SUCCESS", "ref-bad-sig", "GW-BAD-SIG", null);
        ResponseEntity<String> response = post(body,
            "0000000000000000000000000000000000000000000000000000000000000000", Instant.now().toString());
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    private double ambiguousCounterValue() {
        return meterRegistry.counter(AMBIGUOUS_COUNTER).count();
    }

    private static String callbackBody(String status, String reference, String gatewayReference, String reason) {
        return "{\"status\":\"" + status + "\",\"reference\":\"" + reference + "\",\"gatewayReference\":\""
            + gatewayReference + "\",\"reason\":" + (reason == null ? "null" : "\"" + reason + "\"") + "}";
    }

    /** Signs and sends in one place so the timestamp used for the signature and the timestamp
     * sent in the header can never drift apart. */
    private ResponseEntity<String> postSigned(String body) throws Exception {
        String timestamp = Instant.now().toString();
        String signature = hmacHex(timestamp + "." + body);
        return post(body, signature, timestamp);
    }

    private ResponseEntity<String> post(String body, String signature, String timestamp) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-MobileMoney-Signature", signature);
        headers.set("X-MobileMoney-Timestamp", timestamp);
        return rest.postForEntity("http://localhost:" + port + "/webhooks/mobile-money-callback",
            new HttpEntity<>(body, headers), String.class);
    }

    private String hmacHex(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(HMAC_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private void insertDisbursement(UUID tenantId, String idempotencyKey, String gatewayReference) throws Exception {
        TenantContext.set(tenantId);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                 "INSERT INTO payment.disbursement_instruction (tenant_id, idempotency_key, payee_ref, " +
                 "amount, currency, purpose, source_ref, status, gateway_reference) " +
                 "VALUES (?, ?, 'MPESA-IT', 1.00, 'TZS', 'LOAN_DISBURSEMENT', 'it-source', 'PENDING', ?)")) {
            insert.setObject(1, tenantId);
            insert.setString(2, idempotencyKey);
            insert.setString(3, gatewayReference);
            assertThat(insert.executeUpdate()).isEqualTo(1);
        } finally {
            TenantContext.clear();
        }
    }

    private java.util.Map<String, String> readDisbursement(UUID tenantId, String gatewayReference) throws Exception {
        TenantContext.set(tenantId);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                 "SELECT status, gateway_reference FROM payment.disbursement_instruction " +
                 "WHERE tenant_id = ? AND gateway_reference = ?")) {
            select.setObject(1, tenantId);
            select.setString(2, gatewayReference);
            try (ResultSet rs = select.executeQuery()) {
                assertThat(rs.next()).as("row for tenant %s / ref %s not found", tenantId, gatewayReference).isTrue();
                return java.util.Map.of("status", rs.getString(1), "gatewayReference", rs.getString(2));
            }
        } finally {
            TenantContext.clear();
        }
    }
}
