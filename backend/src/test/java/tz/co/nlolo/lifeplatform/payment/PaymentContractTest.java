package tz.co.nlolo.lifeplatform.payment;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.SpecTypeConformance;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.payment.domain.DisbursementInstruction;
import tz.co.nlolo.lifeplatform.payment.domain.PaymentTransaction;
import tz.co.nlolo.lifeplatform.payment.domain.PayoutBatch;
import tz.co.nlolo.lifeplatform.payment.infrastructure.DisbursementInstructionRepository;
import tz.co.nlolo.lifeplatform.payment.infrastructure.PaymentTransactionRepository;
import tz.co.nlolo.lifeplatform.payment.infrastructure.PayoutBatchRepository;
import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-level contract coverage for {@code PaymentController}/{@code MobileMoneyCallbackController}
 * against {@code api/openapi/openapi-payment.yaml} -- the falsifiability gate for Task 8, mirroring
 * the role {@code BillingContractTest} played for M5's own {@code billing} REST surface (this
 * class follows its structure exactly: {@code @Testcontainers} + {@code @AutoConfigureMockMvc} +
 * {@code @SpringBootTest} + {@code SecurityMockMvcRequestPostProcessors.jwt()} +
 * {@code OpenApiValidationMatchers.openApi().isValid(SPEC_PATH)}).
 *
 * <p>Unlike {@code billing}, {@code payment} has no write endpoints of its own to build fixtures
 * through (every payment/disbursement is created by an internal event listener, never by a
 * channel calling this module directly -- see {@code PaymentApi}'s javadoc). Fixtures are seeded
 * directly through the module's own public JPA entities/repositories instead (same technique
 * {@code PaymentApiIntegrationTest} uses via {@code PaymentApiImpl}'s package-private methods --
 * unavailable here since this class lives in {@code payment}, not {@code payment.application} --
 * except for the webhook tests, which need a PENDING row that already carries a
 * {@code gatewayReference}; neither {@code DisbursementInstruction}'s constructor nor its public
 * mutators can express that state (only {@code markCompleted}/{@code markFailed} set
 * {@code gatewayReference}, and both also transition {@code status} away from PENDING), so those
 * three sub-tests seed via a direct JDBC INSERT instead, the same technique
 * {@code MobileMoneyCallbackIntegrationTest} already uses for exactly this reason.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class PaymentContractTest {

    private static final String SPEC_PATH = "api/openapi/openapi-payment.yaml";

    // Deliberately NOT the application.yml default ("dev-only-placeholder-secret") -- a test that
    // happened to reuse that default could pass even if the filter silently ignored the configured
    // property and fell back to some hardcoded value that coincidentally matched. Overridden below
    // via @DynamicPropertySource and recomputed independently here, so a signature only verifies if
    // the filter genuinely read this exact configured secret.
    private static final String TEST_HMAC_SECRET = "payment-contract-test-hmac-secret-8f21";
    // Overridden (not left at the 300s default) so the "stale timestamp" sub-test is deterministic
    // regardless of any future change to that default.
    private static final long TEST_REPLAY_WINDOW_SECONDS = 60;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("mobile-money.callback-hmac-secret", () -> TEST_HMAC_SECRET);
        registry.add("mobile-money.callback-replay-window-seconds", () -> TEST_REPLAY_WINDOW_SECONDS);
    }

    @BeforeAll
    static void applyMigrations() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql",
            "db-migrations/payment/V1__create_payment_schema.sql",
            "db-migrations/payment/V2__grants_rls_money_checks_version_and_tenant_scoped_registries.sql",
            // Needed for the webhook tests: the SECURITY DEFINER tenant-resolution functions
            // PaymentApiImpl.applyGatewayCallback calls to bootstrap TenantContext from a callback
            // that carries no bearer token at all.
            "db-migrations/payment/V3__inbound_callback_tenant_resolver.sql",
            "db-migrations/payment/V6__disbursement_method.sql",
            "db-migrations/payment/V7__q4_2026_partitions.sql",
            "db-migrations/payment/V8__benefit_payout_purposes.sql");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private DataSource dataSource;
    @Autowired private DisbursementInstructionRepository disbursementRepository;
    @Autowired private PaymentTransactionRepository paymentTransactionRepository;
    @Autowired private PayoutBatchRepository payoutBatchRepository;

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    private static RequestPostProcessor staffOf(UUID tenantId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
            .jwt(builder -> builder.claim("tenant_id", tenantId.toString()));
    }

    private static RequestPostProcessor agentOf(UUID tenantId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_AGENTS"))
            .jwt(builder -> builder.claim("tenant_id", tenantId.toString()));
    }

    private static RequestPostProcessor customerOf(UUID tenantId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_CUSTOMERS"))
            .jwt(builder -> builder.claim("tenant_id", tenantId.toString()));
    }

    // --- GET /payments/{paymentRequestId}/status ---------------------------------------------

    /**
     * The brief for this task describes seeding this 200 case "through recordDisbursementRequest"
     * -- but PaymentController.getPaymentStatus calls ONLY PaymentApi.getPaymentStatus, which
     * resolves exclusively against paymentTransactionRepository (the collection ledger), never
     * disbursementRepository. openapi-payment.yaml's own PaymentStatusView.kind description agrees
     * this is deliberate, not an oversight: "Always PAYMENT for /payments/{paymentRequestId}
     * /status". Seeding a DisbursementInstruction here would 404, not 200, so this test seeds a
     * real PaymentTransaction instead -- the only fixture that is genuinely reachable as a 200 on
     * THIS endpoint. The disbursement/kind:DISBURSEMENT case the brief's wording describes is
     * covered below by getStatusByIdempotencyKeyResolvesADisbursementKeyWithKindDisbursement,
     * which really does resolve against the disbursement ledger.
     */
    @Test
    void getPaymentStatusMatchesOpenApiContractWithARealPaymentTransaction() throws Exception {
        UUID tenantId = UUID.randomUUID();
        PaymentTransaction transaction = paymentTransactionRepository.save(
            new PaymentTransaction(tenantId, "contract-pay-key-" + UUID.randomUUID(),
                "MPESA-0712340001", new BigDecimal("2500.00"), "TZS", "invoice-contract-1"));

        mockMvc.perform(get("/payments/" + transaction.getPaymentTransactionId() + "/status")
                .with(staffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            // openApi().isValid() does NOT check primitive JSON types (measured: a type:string field
            // emitted as a number reports hasErrors=false) -- this covers that difference, and here
            // it guards Money.amount, which must stay a decimal STRING and never become a float.
            .andExpect(SpecTypeConformance.matchesDeclaredTypes(SPEC_PATH, "PaymentStatusView"))
            .andExpect(jsonPath("$.id").value(transaction.getPaymentTransactionId().toString()))
            .andExpect(jsonPath("$.kind").value("PAYMENT"))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.amount.amount").value("2500.00"))
            .andExpect(jsonPath("$.amount.currencyCode").value("TZS"));
    }

    @Test
    void getPaymentStatusReturns404ForUnknownId() throws Exception {
        mockMvc.perform(get("/payments/" + UUID.randomUUID() + "/status")
                .with(staffOf(UUID.randomUUID())))
            .andExpect(status().isNotFound())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("PAYMENT_NOT_FOUND"));
    }

    /** Proves Task 8 Step 1's REALM_CUSTOMERS narrowing is real, not just documented in the
     * controller/spec javadoc -- payment's tables carry no party_id/policy_number column to
     * perform an object-level ownership check against, so the realm is excluded entirely. */
    @Test
    void getPaymentStatusRejectsACustomerTokenWith403() throws Exception {
        UUID tenantId = UUID.randomUUID();
        PaymentTransaction transaction = paymentTransactionRepository.save(
            new PaymentTransaction(tenantId, "contract-pay-key-" + UUID.randomUUID(),
                "MPESA-0712340002", new BigDecimal("2500.00"), "TZS", "invoice-contract-2"));

        mockMvc.perform(get("/payments/" + transaction.getPaymentTransactionId() + "/status")
                .with(customerOf(tenantId)))
            .andExpect(status().isForbidden())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    // --- GET /payments/status-by-key ----------------------------------------------------------

    @Test
    void getStatusByIdempotencyKeyResolvesADisbursementKeyWithKindDisbursement() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String idempotencyKey = "contract-disb-key-" + UUID.randomUUID();
        DisbursementInstruction disbursement = disbursementRepository.save(
            new DisbursementInstruction(tenantId, idempotencyKey, "MPESA-0712340003",
                new BigDecimal("7500.00"), "TZS", "LOAN_DISBURSEMENT", "loan-contract-1"));

        mockMvc.perform(get("/payments/status-by-key").param("idempotencyKey", idempotencyKey)
                .with(agentOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.id").value(disbursement.getDisbursementId().toString()))
            .andExpect(jsonPath("$.kind").value("DISBURSEMENT"))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.amount.amount").value("7500.00"));
    }

    @Test
    void getStatusByIdempotencyKeyResolvesACollectionKeyWithKindPayment() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String idempotencyKey = "contract-pay-key-" + UUID.randomUUID();
        PaymentTransaction transaction = paymentTransactionRepository.save(
            new PaymentTransaction(tenantId, idempotencyKey, "MPESA-0712340004",
                new BigDecimal("3300.00"), "TZS", "invoice-contract-3"));

        mockMvc.perform(get("/payments/status-by-key").param("idempotencyKey", idempotencyKey)
                .with(staffOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.id").value(transaction.getPaymentTransactionId().toString()))
            .andExpect(jsonPath("$.kind").value("PAYMENT"))
            .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void getStatusByIdempotencyKeyReturns404ForAnUnknownKey() throws Exception {
        mockMvc.perform(get("/payments/status-by-key")
                .param("idempotencyKey", "no-such-key-" + UUID.randomUUID())
                .with(staffOf(UUID.randomUUID())))
            .andExpect(status().isNotFound())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("PAYMENT_NOT_FOUND"));
    }

    // --- GET /payout-batches/{batchId} --------------------------------------------------------

    private record BatchFixture(UUID tenantId, UUID batchId) {}

    /** Mirrors PaymentApiIntegrationTest.payoutBatchStatusIsDerivedFromItsMembersAcrossAllThreeStates'
     * PARTIAL_FAILURE leg: two members assigned to one batch, one COMPLETED and one FAILED, both
     * terminal -- the only member mix that reaches PARTIAL_FAILURE rather than IN_PROGRESS or
     * COMPLETED. */
    private BatchFixture seedPartialFailureBatch() {
        UUID tenantId = UUID.randomUUID();
        PayoutBatch batch = payoutBatchRepository.save(new PayoutBatch(tenantId, "MATURITY_BATCH"));

        UUID succeedingId = disbursementRepository.save(
            new DisbursementInstruction(tenantId, "contract-batch-key-1-" + UUID.randomUUID(),
                "MPESA-0712340005", new BigDecimal("1000.00"), "TZS", "MATURITY_PAYOUT", "policy-contract-1"))
            .getDisbursementId();
        UUID failingId = disbursementRepository.save(
            new DisbursementInstruction(tenantId, "contract-batch-key-2-" + UUID.randomUUID(),
                "MPESA-0712340006", new BigDecimal("2000.00"), "TZS", "MATURITY_PAYOUT", "policy-contract-2"))
            .getDisbursementId();

        // Re-fetches before each mutation rather than reusing the Java instance the initial save()
        // returned -- mirroring PaymentApiIntegrationTest's own assignToBatch helper. Necessary
        // because DisbursementInstruction's @Id includes createdAt (an Instant field initialized in
        // Java with nanosecond precision, but round-tripped through a TIMESTAMPTZ column at
        // microsecond precision): reusing the original in-memory instance across three separate
        // save() calls (each its own transaction, so the entity is detached in between) makes
        // Hibernate's merge() compare that slightly-off in-memory id against the row's real
        // persisted id and throw "identifier ... was altered" -- confirmed empirically, not assumed.
        assignAndComplete(tenantId, succeedingId, batch.getBatchId());
        assignAndFail(tenantId, failingId, batch.getBatchId());

        return new BatchFixture(tenantId, batch.getBatchId());
    }

    private void assignAndComplete(UUID tenantId, UUID disbursementId, UUID batchId) {
        DisbursementInstruction instruction =
            disbursementRepository.findByDisbursementIdAndTenantId(disbursementId, tenantId).orElseThrow();
        instruction.assignToBatch(batchId);
        disbursementRepository.save(instruction);

        DisbursementInstruction reloaded =
            disbursementRepository.findByDisbursementIdAndTenantId(disbursementId, tenantId).orElseThrow();
        reloaded.markCompleted("MM-CONTRACT-OK");
        disbursementRepository.save(reloaded);
    }

    private void assignAndFail(UUID tenantId, UUID disbursementId, UUID batchId) {
        DisbursementInstruction instruction =
            disbursementRepository.findByDisbursementIdAndTenantId(disbursementId, tenantId).orElseThrow();
        instruction.assignToBatch(batchId);
        disbursementRepository.save(instruction);

        DisbursementInstruction reloaded =
            disbursementRepository.findByDisbursementIdAndTenantId(disbursementId, tenantId).orElseThrow();
        reloaded.markFailed("MM-CONTRACT-FAIL");
        disbursementRepository.save(reloaded);
    }

    @Test
    void getPayoutBatchMatchesOpenApiContractWithARealPartialFailureBatch() throws Exception {
        BatchFixture fixture = seedPartialFailureBatch();

        mockMvc.perform(get("/payout-batches/" + fixture.batchId()).with(staffOf(fixture.tenantId())))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.batchId").value(fixture.batchId().toString()))
            .andExpect(jsonPath("$.batchType").value("MATURITY_BATCH"))
            .andExpect(jsonPath("$.status").value("PARTIAL_FAILURE"))
            .andExpect(jsonPath("$.disbursementCount").value(2))
            .andExpect(jsonPath("$.failedCount").value(1));
    }

    /** @PreAuthorize on getPayoutBatch admits ONLY REALM_STAFF -- unlike the other two payment
     * endpoints, REALM_AGENTS is excluded here too, not just REALM_CUSTOMERS. */
    @Test
    void getPayoutBatchRejectsAnAgentTokenWith403() throws Exception {
        BatchFixture fixture = seedPartialFailureBatch();

        mockMvc.perform(get("/payout-batches/" + fixture.batchId()).with(agentOf(fixture.tenantId())))
            .andExpect(status().isForbidden())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    void getPayoutBatchReturns404ForAnUnknownBatch() throws Exception {
        mockMvc.perform(get("/payout-batches/" + UUID.randomUUID()).with(staffOf(UUID.randomUUID())))
            .andExpect(status().isNotFound())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("PAYMENT_NOT_FOUND"));
    }

    // --- GET /disbursements?purpose=&sourceRef= ----------------------------------------------

    private void seedClaimPayout(UUID tenantId, String claimId, String method, String status) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                 "INSERT INTO payment.disbursement_instruction (tenant_id, idempotency_key, payee_ref, " +
                 "amount, currency, purpose, source_ref, status, method) " +
                 "VALUES (?, ?, 'Lender Bank Ltd', 800000.00, 'TZS', 'CLAIM_SETTLEMENT', ?, ?, ?)")) {
            insert.setObject(1, tenantId);
            insert.setString(2, "claim-" + claimId + "-" + UUID.randomUUID());
            insert.setString(3, claimId);
            insert.setString(4, status);
            insert.setString(5, method);
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }
    }

    private static RequestPostProcessor claimsManagerOf(UUID tenantId) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_CLAIMS_MANAGER"),
                new SimpleGrantedAuthority("ROLE_REALM_STAFF"))
            .jwt(builder -> builder.claim("tenant_id", tenantId.toString()));
    }

    /** The claims manager approved this payout and then could not see it: it sat AWAITING
     * finance with nothing on the claim page to say so. Only this claim's payout comes back. */
    @Test
    void theClaimsManagerReadsTheirClaimsPayoutAndSeesItWaitsOnFinance() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String claimId = UUID.randomUUID().toString();
        seedClaimPayout(tenantId, claimId, "EFT", "AWAITING_EXECUTION");
        seedClaimPayout(tenantId, UUID.randomUUID().toString(), "EFT", "AWAITING_EXECUTION"); // another claim

        mockMvc.perform(get("/disbursements").param("purpose", "CLAIM_SETTLEMENT").param("sourceRef", claimId)
                .with(claimsManagerOf(tenantId)))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].method").value("EFT"))
            .andExpect(jsonPath("$[0].status").value("AWAITING_EXECUTION"))
            .andExpect(jsonPath("$[0].amount.amount").value("800000.00"))
            .andExpect(jsonPath("$[0].payeeRef").value("Lender Bank Ltd"))
            .andExpect(jsonPath("$[0].executedAt").doesNotExist())
            // The finance officer's subject is never part of this view.
            .andExpect(jsonPath("$[0].executedBy").doesNotExist());
    }

    @Test
    void anotherTenantsPayoutIsNotReadable() throws Exception {
        String claimId = UUID.randomUUID().toString();
        seedClaimPayout(UUID.randomUUID(), claimId, "EFT", "AWAITING_EXECUTION");

        mockMvc.perform(get("/disbursements").param("purpose", "CLAIM_SETTLEMENT").param("sourceRef", claimId)
                .with(claimsManagerOf(UUID.randomUUID())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    /** Payee and rail are internal: plain staff without a claims or finance role get nothing. */
    @Test
    void aStaffTokenWithoutAClaimsOrFinanceRoleIsRefused() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String claimId = UUID.randomUUID().toString();
        seedClaimPayout(tenantId, claimId, "MOBILE_MONEY", "COMPLETED");

        mockMvc.perform(get("/disbursements").param("purpose", "CLAIM_SETTLEMENT").param("sourceRef", claimId)
                .with(staffOf(tenantId)))
            .andExpect(status().isForbidden())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));
    }

    // --- POST /webhooks/mobile-money-callback -------------------------------------------------

    /** Seeds a PENDING disbursement that already carries the gatewayReference the callback will
     * report on -- simulating the real sequence where the outbound gateway call already recorded
     * the aggregator's reference before this callback ever arrives. Raw JDBC, not the entity's
     * public API: DisbursementInstruction has no setter for gatewayReference that leaves status at
     * PENDING (only markCompleted/markFailed set it, and both also leave PENDING as part of the
     * same call) -- the same limitation MobileMoneyCallbackIntegrationTest's own insertDisbursement
     * helper works around identically. */
    private void seedPendingDisbursementWithGatewayReference(UUID tenantId, String idempotencyKey,
                                                               String gatewayReference) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                 "INSERT INTO payment.disbursement_instruction (tenant_id, idempotency_key, payee_ref, " +
                 "amount, currency, purpose, source_ref, status, gateway_reference) " +
                 "VALUES (?, ?, 'MPESA-CONTRACT-CB', 5000.00, 'TZS', 'MATURITY_PAYOUT', 'contract-source', 'PENDING', ?)")) {
            insert.setObject(1, tenantId);
            insert.setString(2, idempotencyKey);
            insert.setString(3, gatewayReference);
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }
    }

    private static String callbackBody(String status, String reference, String gatewayReference, String reason) {
        return "{\"status\":\"" + status + "\",\"reference\":\"" + reference + "\",\"gatewayReference\":\""
            + gatewayReference + "\",\"reason\":" + (reason == null ? "null" : "\"" + reason + "\"") + "}";
    }

    private static String hmacHex(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(TEST_HMAC_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    /** State change, not just status code: the whole point of this test is proving the callback
     * genuinely mutated the ledger row, not merely that the HTTP layer returned 200 regardless of
     * what happened underneath (MobileMoneyCallbackController swallows every exception and always
     * acks 200 by design -- see its own javadoc -- so the status code alone proves nothing here). */
    @Test
    void correctlySignedCallbackCompletesTheDisbursement() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String gatewayReference = "GW-CONTRACT-HAPPY-" + UUID.randomUUID();
        seedPendingDisbursementWithGatewayReference(tenantId, "contract-cb-key-" + UUID.randomUUID(), gatewayReference);

        String body = callbackBody("SUCCESS", "contract-ref-1", gatewayReference, null);
        String timestamp = Instant.now().toString();
        String signature = hmacHex(timestamp + "." + body);

        mockMvc.perform(post("/webhooks/mobile-money-callback")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-MobileMoney-Signature", signature)
                .header("X-MobileMoney-Timestamp", timestamp)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH));

        DisbursementInstruction updated = disbursementRepository
            .findByGatewayReferenceAndTenantId(gatewayReference, tenantId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("COMPLETED");
    }

    /** Negative control: a well-formed, schema-valid body with the WRONG signature must 401 before
     * ever reaching the controller -- proving the filter's HMAC verification genuinely executes
     * rather than the wiring merely permitting everything through. Not the "schema-invalid body"
     * gotcha M4's BillingContractTest hit (openApi().isValid(SPEC_PATH) also validates the
     * REQUEST): this body and its headers are all schema-valid, only the signature VALUE is wrong,
     * so the matcher is safe to keep here. */
    @Test
    void wrongSignatureIsRejectedWith401() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String gatewayReference = "GW-CONTRACT-BADSIG-" + UUID.randomUUID();
        seedPendingDisbursementWithGatewayReference(tenantId, "contract-cb-key-" + UUID.randomUUID(), gatewayReference);

        String body = callbackBody("SUCCESS", "contract-ref-2", gatewayReference, null);
        String timestamp = Instant.now().toString();
        String wrongSignature = "0".repeat(64); // valid hex shape, guaranteed not to match

        mockMvc.perform(post("/webhooks/mobile-money-callback")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-MobileMoney-Signature", wrongSignature)
                .header("X-MobileMoney-Timestamp", timestamp)
                .content(body))
            .andExpect(status().isUnauthorized())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("CALLBACK_AUTH_FAILED"));

        DisbursementInstruction untouched = disbursementRepository
            .findByGatewayReferenceAndTenantId(gatewayReference, tenantId).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo("PENDING");
    }

    /** Negative control 2: a CORRECTLY signed body -- signature computed over the same stale
     * timestamp that is sent, so this proves the replay-window check itself, not merely a signature
     * mismatch (withinReplayWindow is checked in MobileMoneyHmacFilter before the signature is
     * even verified, so either bug would 401, but only this test isolates the timestamp check). */
    @Test
    void correctSignatureWithAStaleTimestampIsRejectedWith401() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String gatewayReference = "GW-CONTRACT-STALE-" + UUID.randomUUID();
        seedPendingDisbursementWithGatewayReference(tenantId, "contract-cb-key-" + UUID.randomUUID(), gatewayReference);

        String body = callbackBody("SUCCESS", "contract-ref-3", gatewayReference, null);
        // TEST_REPLAY_WINDOW_SECONDS is overridden to 60 above; twice that is unambiguously stale.
        String staleTimestamp = Instant.now().minusSeconds(TEST_REPLAY_WINDOW_SECONDS * 2).toString();
        String correctSignatureForStaleTimestamp = hmacHex(staleTimestamp + "." + body);

        mockMvc.perform(post("/webhooks/mobile-money-callback")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-MobileMoney-Signature", correctSignatureForStaleTimestamp)
                .header("X-MobileMoney-Timestamp", staleTimestamp)
                .content(body))
            .andExpect(status().isUnauthorized())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC_PATH))
            .andExpect(jsonPath("$.errorCode").value("CALLBACK_AUTH_FAILED"));

        DisbursementInstruction untouched = disbursementRepository
            .findByGatewayReferenceAndTenantId(gatewayReference, tenantId).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo("PENDING");
    }
}
