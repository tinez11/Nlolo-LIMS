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
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
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
    private static final String UNKNOWN_STATUS_COUNTER = "lifeplatform_payment_callback_unknown_status_total";

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
            "db-migrations/payment/V3__inbound_callback_tenant_resolver.sql",
            // M5 final-review fix wave: V4 adds the id-keyed resolvers C1's fallback calls, and
            // widens both status CHECKs for C2's IN_DOUBT. Both are load-bearing below.
            "db-migrations/payment/V4__in_doubt_status_and_id_based_callback_resolvers.sql");
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

    /**
     * Pins Important 5 (part 2): reproduces the reviewer's exact raw-socket probe. A request
     * carrying two JUNK (present, wrong-value, NOT missing) auth headers used to sail straight
     * past the header-presence check and into an unbounded {@code readAllBytes()} -- so a
     * declared {@code Content-Length: 50000000} with only 16 bytes actually sent made the server
     * hang 5+ seconds waiting for the rest of a body that was never coming, before the signature
     * check (which needed the whole body anyway) ever got a chance to fail it. A plain
     * {@code TestRestTemplate}/{@code HttpEntity} call cannot reproduce this: those clients always
     * compute a truthful {@code Content-Length} for the bytes they actually send, so this needs a
     * raw socket that can lie about how much body is coming and then simply stop sending.
     *
     * <p>Asserts the response now comes back FAST (well under the socket's own generous 5s read
     * timeout) and is 401 -- proving the fix (checking the declared length upfront, and bounding
     * the actual read via {@code readNBytes(cap + 1)} regardless of what the header claims) closes
     * the hang rather than merely changing its error message after still blocking.
     */
    @Test
    void oversizedDeclaredContentLengthWithJunkAuthHeadersFailsFastInsteadOfHanging() throws Exception {
        String partialBody = "1234567890123456"; // 16 bytes -- deliberately far short of the lie below.
        // The timestamp must be a VALID, CURRENT instant -- a malformed one would be rejected by
        // the (already-correct) stale-timestamp check before ever reaching the vulnerable
        // body-read code, which would make this test pass for the wrong reason regardless of
        // whether the size-cap fix is present. Only the SIGNATURE is junk (wrong value, still
        // present) -- exactly the reviewer's scenario: present-but-wrong headers, not missing ones.
        String request = "POST /webhooks/mobile-money-callback HTTP/1.1\r\n"
            + "Host: localhost\r\n"
            + "Content-Type: application/json\r\n"
            + "X-MobileMoney-Signature: junk-signature-value\r\n"
            + "X-MobileMoney-Timestamp: " + Instant.now() + "\r\n"
            + "Content-Length: 50000000\r\n" // The lie: ~50MB declared, 16 bytes actually sent.
            + "Connection: close\r\n"
            + "\r\n"
            + partialBody;

        long startedAt = System.nanoTime();
        String statusLine;
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5000); // Generous but bounded -- the pre-fix bug hung 5+ seconds;
                                        // this must not merely trade a hang for a timeout exception.
            try (OutputStream out = socket.getOutputStream()) {
                out.write(request.getBytes(StandardCharsets.US_ASCII));
                out.flush();
                // Deliberately do NOT send the remaining ~49999984 bytes Content-Length promised --
                // this is exactly the attacker's move: declare a huge body, send a token amount,
                // then go quiet.
                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))) {
                    statusLine = in.readLine();
                }
            }
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(statusLine).as("response status line").contains("401");
        assertThat(elapsed).as("must fail fast, not hang waiting for a body that will never arrive")
            .isLessThan(Duration.ofSeconds(3));
    }

    /**
     * Review fix (C1), the whole point of it. An IN_DOUBT row with {@code gateway_reference = NULL}
     * -- the state a transport failure leaves behind, and the population most likely to need webhook
     * recovery -- must be resolvable by the aggregator's later notification. The PRIMARY route
     * cannot possibly work: {@code resolve_disbursement_tenant} keys on {@code gateway_reference},
     * which is null here, and V2's supporting index is literally
     * {@code WHERE gateway_reference IS NOT NULL}. Before this fix the callback resolved to nothing,
     * logged a WARN, acked 200, and the payout was unrecoverable forever.
     *
     * <p>The fallback uses the callback's own {@code reference} field -- the merchant reference THIS
     * PLATFORM generated and sent to the rail, i.e. {@code disbursementId.toString()}. Asserts three
     * things, because the first alone would be satisfied by several wrong implementations: the row
     * reaches COMPLETED (so C2's IN_DOUBT is genuinely non-terminal, not a dead end), the
     * aggregator's reference is now PERSISTED (so a redelivery resolves via the primary route from
     * here on -- the fix repairs the row rather than depending on the fallback forever), and the
     * whole thing ran under real app_role RLS.
     */
    @Test
    void anInDoubtRowWithNoGatewayReferenceIsRecoveredViaTheCallbacksOwnMerchantReference() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID disbursementId = insertDisbursementWithStatusAndNoGatewayReference(tenantId, "it-key-indoubt", "IN_DOUBT");

        // gatewayReference is a value NO row carries -- so the primary resolver must resolve to
        // nothing and the fallback must be what rescues this. If the assertion below passed while
        // the fallback were absent, this test would be proving nothing.
        String body = callbackBody("SUCCESS", disbursementId.toString(), "GW-NEVER-SEEN-BEFORE", null);
        ResponseEntity<String> response = postSigned(body);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var row = readDisbursementById(tenantId, disbursementId);
        assertThat(row.get("status")).as("an IN_DOUBT row must be recoverable, not stranded").isEqualTo("COMPLETED");
        assertThat(row.get("gatewayReference"))
            .as("the aggregator's reference must be written on the way through, repairing the null")
            .isEqualTo("GW-NEVER-SEEN-BEFORE");
    }

    /** The same fallback for a PENDING row (the C3 scenario: a pool timeout inside phase 3 leaves a
     * PENDING row with no gateway_reference after the rail already accepted). Separate from the
     * IN_DOUBT case above because the two arise from different failures and a fix that only handled
     * one would leave a real hole. */
    @Test
    void aPendingRowWithNoGatewayReferenceIsAlsoRecoverableViaTheMerchantReference() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID disbursementId = insertDisbursementWithStatusAndNoGatewayReference(tenantId, "it-key-pending-null", "PENDING");

        ResponseEntity<String> response = postSigned(
            callbackBody("FAILED", disbursementId.toString(), "GW-LATE-DECLINE", "INSUFFICIENT_FLOAT"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var row = readDisbursementById(tenantId, disbursementId);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("gatewayReference")).isEqualTo("GW-LATE-DECLINE");
    }

    /**
     * Review fix (C1): the fallback must NOT mask a genuine ambiguity. Two tenants share one
     * gateway_reference (AMBIGUOUS), and the callback ALSO carries a perfectly resolvable merchant
     * reference pointing at a third, unrelated row. The correct behaviour is to refuse everything:
     * report AMBIGUOUS, increment the ambiguous counter, and leave all three rows untouched --
     * because a reference collision means we cannot trust which row this notification is about at
     * all, regardless of what else in the payload happens to resolve.
     */
    @Test
    void anAmbiguousGatewayReferenceIsStillAmbiguousEvenWhenTheMerchantReferenceWouldResolve() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID tenantC = UUID.randomUUID();
        insertDisbursement(tenantA, "it-key-amb-mask-a", "GW-SHARED-MASK");
        insertDisbursement(tenantB, "it-key-amb-mask-b", "GW-SHARED-MASK");
        UUID unrelatedId = insertDisbursementWithStatusAndNoGatewayReference(tenantC, "it-key-amb-mask-c", "IN_DOUBT");

        double before = ambiguousCounterValue();

        ResponseEntity<String> response = postSigned(
            callbackBody("SUCCESS", unrelatedId.toString(), "GW-SHARED-MASK", null));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(ambiguousCounterValue()).as("the ambiguity must still be reported").isEqualTo(before + 1);
        assertThat(readDisbursement(tenantA, "GW-SHARED-MASK").get("status")).isEqualTo("PENDING");
        assertThat(readDisbursement(tenantB, "GW-SHARED-MASK").get("status")).isEqualTo("PENDING");
        assertThat(readDisbursementById(tenantC, unrelatedId).get("status"))
            .as("the fallback must not have quietly applied the outcome to the row the merchant "
                + "reference points at -- an ambiguous callback is not trustworthy for ANY row")
            .isEqualTo("IN_DOUBT");
    }

    /**
     * Review fix (C2, second half). {@code isSuccess} used to be
     * {@code "SUCCESS".equalsIgnoreCase(status)} with every other value treated as a TERMINAL
     * FAILURE, on an unvalidated {@code @NotBlank String}. So a vendor code, a typo, or an
     * aggregator's renamed status silently drove a real financial transition to FAILED -- publishing
     * {@code DisbursementFailed} and making policyloan write a REVERSAL and release the encumbrance.
     *
     * <p>Now: nothing is applied, and the ERROR is counted so a dialect drift is visible rather than
     * silent. Asserts the row is genuinely untouched (still PENDING) AND the counter moved -- the
     * status assertion alone would also pass if the request had 500'd before reaching any logic.
     */
    @Test
    void anUnrecognizedCallbackStatusAppliesNothingAndIncrementsTheUnknownStatusCounter() throws Exception {
        UUID tenantId = UUID.randomUUID();
        insertDisbursement(tenantId, "it-key-unknown-status", "GW-UNKNOWN-STATUS");

        double before = unknownStatusCounterValue();

        // A plausible vendor-specific status this platform has never heard of.
        ResponseEntity<String> response = postSigned(
            callbackBody("TXN_QUEUED_AT_SWITCH", "ref-unknown", "GW-UNKNOWN-STATUS", null));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(readDisbursement(tenantId, "GW-UNKNOWN-STATUS").get("status"))
            .as("an unrecognized status string must never drive a financial state transition")
            .isEqualTo("PENDING");
        assertThat(unknownStatusCounterValue()).isEqualTo(before + 1);
    }

    /** A recognized NON-TERMINAL status (a "still processing" notification) must also apply nothing,
     * but must NOT be alerted on -- otherwise the unknown-status alert fires on normal traffic, gets
     * muted, and then no longer works for the case it was written for. */
    @Test
    void aRecognizedNonTerminalCallbackStatusAppliesNothingAndDoesNotAlert() throws Exception {
        UUID tenantId = UUID.randomUUID();
        insertDisbursement(tenantId, "it-key-processing", "GW-PROCESSING");

        double before = unknownStatusCounterValue();

        ResponseEntity<String> response = postSigned(
            callbackBody("PROCESSING", "ref-processing", "GW-PROCESSING", null));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(readDisbursement(tenantId, "GW-PROCESSING").get("status")).isEqualTo("PENDING");
        assertThat(unknownStatusCounterValue()).as("recognized non-terminal statuses are not alerts")
            .isEqualTo(before);
    }

    private double ambiguousCounterValue() {
        return meterRegistry.counter(AMBIGUOUS_COUNTER).count();
    }

    private double unknownStatusCounterValue() {
        return meterRegistry.counter(UNKNOWN_STATUS_COUNTER).count();
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

    /** Seeds the exact row shape C1 is about: NO gateway_reference at all, which is what a transport
     * failure (or a pool timeout inside phase 3) leaves behind. Returns the generated
     * disbursement_id, because that id -- echoed back by the aggregator as the callback's
     * {@code reference} -- is the ONLY handle such a row has. Asserts the insert's affected-row count
     * before trusting the state, per this project's own vacuous-test rule. */
    private UUID insertDisbursementWithStatusAndNoGatewayReference(UUID tenantId, String idempotencyKey, String status)
            throws Exception {
        TenantContext.set(tenantId);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                 "INSERT INTO payment.disbursement_instruction (tenant_id, idempotency_key, payee_ref, " +
                 "amount, currency, purpose, source_ref, status, gateway_reference) " +
                 "VALUES (?, ?, 'MPESA-IT', 1.00, 'TZS', 'LOAN_DISBURSEMENT', 'it-source', ?, NULL) " +
                 "RETURNING disbursement_id")) {
            insert.setObject(1, tenantId);
            insert.setString(2, idempotencyKey);
            insert.setString(3, status);
            try (ResultSet rs = insert.executeQuery()) {
                assertThat(rs.next()).as("insert must have produced exactly one row").isTrue();
                return rs.getObject(1, UUID.class);
            }
        } finally {
            TenantContext.clear();
        }
    }

    private java.util.Map<String, String> readDisbursementById(UUID tenantId, UUID disbursementId) throws Exception {
        TenantContext.set(tenantId);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                 "SELECT status, gateway_reference FROM payment.disbursement_instruction " +
                 "WHERE tenant_id = ? AND disbursement_id = ?")) {
            select.setObject(1, tenantId);
            select.setObject(2, disbursementId);
            try (ResultSet rs = select.executeQuery()) {
                assertThat(rs.next()).as("row for tenant %s / id %s not found", tenantId, disbursementId).isTrue();
                String reference = rs.getString(2);
                return java.util.Map.of("status", rs.getString(1),
                    "gatewayReference", reference == null ? "<null>" : reference);
            }
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
