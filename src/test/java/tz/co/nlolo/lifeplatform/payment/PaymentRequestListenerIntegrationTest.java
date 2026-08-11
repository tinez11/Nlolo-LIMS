package tz.co.nlolo.lifeplatform.payment;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.audit.domain.AuditLogEntry;
import tz.co.nlolo.lifeplatform.audit.infrastructure.AuditLogRepository;
import tz.co.nlolo.lifeplatform.payment.domain.DisbursementInstruction;
import tz.co.nlolo.lifeplatform.payment.domain.PaymentGatewayPort;
import tz.co.nlolo.lifeplatform.payment.infrastructure.DisbursementInstructionRepository;
import tz.co.nlolo.lifeplatform.payment.infrastructure.MobileMoneyGatewayAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives payment.application.PaymentRequestListener through real
 * DomainEventEnvelope<LoanDisbursementRequested> publications, against an in-process WireMock
 * standing in for the mobile-money rail (mobile-money.base-url is redirected to it via
 * {@code @DynamicPropertySource}, the same mechanism {@code datasourceProperties} already uses
 * for Testcontainers Postgres).
 *
 * <p>Events are published via the real {@code ApplicationEventPublisher} from inside a
 * {@code TransactionTemplate}-wrapped commit, exactly as
 * {@code policyloan.PolicyLoanApiIntegrationTest} already does elsewhere in this codebase to
 * make an {@code AFTER_COMMIT} listener actually fire -- publishing outside any transaction would
 * never schedule the {@code @TransactionalEventListener}'s synchronization at all, and the
 * listener would silently never run.
 *
 * <p>{@link TransactionGuardedGatewayConfig} replaces the real {@code PaymentGatewayPort} bean
 * with one that asserts no transaction is active at the instant the gateway is called, for every
 * test in this class -- a standing regression guard against the exact bug PaymentRequestListener
 * was fixed for (a REQUIRED call from an AFTER_COMMIT listener silently failing to commit unless
 * routed through its own REQUIRES_NEW TransactionTemplate call). If a future change collapses the
 * three phases back into one transaction spanning the gateway call, every test below fails loudly
 * with an AssertionError instead of merely leaving the existing assertions accidentally still
 * green.
 *
 * <p><b>Requires the explicit {@code @Import} below</b> -- Spring Boot's automatic pickup of a
 * nested {@code @TestConfiguration} class does NOT apply here because this test fixes
 * {@code @SpringBootTest(classes = Application.class)} explicitly; without the {@code @Import},
 * the nested class is silently never registered and the guard bean is never wired in at all
 * (verified empirically: an unconditional-throw version of the guard never fired until
 * {@code @Import} was added).
 */
@SpringBootTest(classes = Application.class)
@Import(PaymentRequestListenerIntegrationTest.TransactionGuardedGatewayConfig.class)
@Testcontainers
class PaymentRequestListenerIntegrationTest {

    /**
     * Wraps the real, WireMock-backed {@link MobileMoneyGatewayAdapter} so every call the
     * listener makes through {@code PaymentGatewayPort} is checked for an active transaction
     * first. {@code @Primary} makes it win over the adapter's own {@code @Component} registration
     * wherever {@code PaymentGatewayPort} is autowired (i.e. into {@code PaymentRequestListener}).
     */
    @TestConfiguration
    static class TransactionGuardedGatewayConfig {
        @Bean
        @Primary
        PaymentGatewayPort transactionGuardedGateway(MobileMoneyGatewayAdapter delegate) {
            return new PaymentGatewayPort() {
                @Override
                public GatewayResult submitDisbursement(GatewayDisbursementRequest request) {
                    assertNoTransactionActive();
                    return delegate.submitDisbursement(request);
                }

                @Override
                public GatewayResult submitCollection(GatewayCollectionRequest request) {
                    assertNoTransactionActive();
                    return delegate.submitCollection(request);
                }

                private void assertNoTransactionActive() {
                    if (TransactionSynchronizationManager.isActualTransactionActive()) {
                        throw new AssertionError("PaymentRequestListener called the gateway with "
                            + "an active transaction -- constraint 2 (never hold a transaction "
                            + "across the outbound HTTP call) is violated");
                    }
                }
            };
        }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static WireMockServer wireMock;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @DynamicPropertySource
    static void mobileMoneyProperties(DynamicPropertyRegistry registry) {
        registry.add("mobile-money.base-url", () -> wireMock.baseUrl());
    }

    @BeforeAll
    static void startGatewayAndApplyMigrations() throws Exception {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/payment/V1__create_payment_schema.sql",
            "db-migrations/payment/V2__grants_rls_money_checks_version_and_tenant_scoped_registries.sql");
    }

    @AfterAll
    static void stopGateway() { wireMock.stop(); }

    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DisbursementInstructionRepository disbursementRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private ObjectMapper objectMapper;

    private TransactionTemplate transactionTemplate;

    @AfterEach
    void resetAfterEach() {
        TenantContext.clear();
        wireMock.resetAll();
    }

    private void publishLoanDisbursementRequested(DomainEventEnvelope<Map<String, Object>> envelope) {
        if (transactionTemplate == null) {
            transactionTemplate = new TransactionTemplate(transactionManager);
        }
        // AFTER_COMMIT listeners run synchronously on the same thread once this commit returns,
        // so nothing further (no await/sleep) is needed after this call.
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(envelope));
    }

    private DomainEventEnvelope<Map<String, Object>> loanDisbursementRequested(
            UUID tenantId, String idempotencyKey, UUID loanId, String payeeRef, String amount, String currency) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("idempotencyKey", idempotencyKey);
        payload.put("loanId", loanId);
        payload.put("payeeRef", payeeRef); // deliberately nullable -- LinkedHashMap, not Map.of
        payload.put("amount", Map.of("amount", amount, "currencyCode", currency));
        return DomainEventEnvelope.of("policyloan.LoanDisbursementRequested", tenantId, payload);
    }

    @Test
    void acceptedDisbursementCompletesTheInstructionAndPublishesDisbursementCompleted() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"ACCEPTED\",\"gatewayReference\":\"MM-ABC123\"}")));

        UUID tenantId = UUID.randomUUID();
        UUID loanId = UUID.randomUUID();
        String idempotencyKey = "loan-" + loanId;
        Instant before = Instant.now();

        publishLoanDisbursementRequested(
            loanDisbursementRequested(tenantId, idempotencyKey, loanId, "MPESA-0712345678", "50000.00", "TZS"));

        DisbursementInstruction instruction = disbursementRepository
            .findByIdempotencyKeyAndTenantId(idempotencyKey, tenantId)
            .orElseThrow(() -> new AssertionError("Expected a disbursement_instruction row for key " + idempotencyKey));
        assertThat(instruction.getStatus()).isEqualTo("COMPLETED");
        assertThat(instruction.getGatewayReference()).isEqualTo("MM-ABC123");

        // Falsifiable: proves payment.DisbursementCompleted was genuinely published (not merely
        // that the row changed) -- audit.DomainEventAuditListener persists every published
        // envelope AFTER_COMMIT, the same idiom PolicyApiIntegrationTest uses for policy.PolicyIssued.
        List<AuditLogEntry> auditRows = auditLogRepository.findByTenantIdAndEventTypeAndOccurredAtBetween(
            tenantId, "payment.DisbursementCompleted", before.minusSeconds(5), Instant.now().plusSeconds(5));
        assertThat(auditRows).hasSize(1);
        JsonNode payload = objectMapper.readTree(auditRows.get(0).getPayload());
        assertThat(payload.path("disbursementId").asText()).isEqualTo(instruction.getDisbursementId().toString());
        assertThat(payload.path("gatewayReference").asText()).isEqualTo("MM-ABC123");

        wireMock.verify(exactly(1), postRequestedFor(urlPathEqualTo("/disburse")));
    }

    @Test
    void rejectedDisbursementFailsTheInstructionAndPublishesDisbursementFailedWithTheGatewayReason() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"REJECTED\",\"reason\":\"INSUFFICIENT_FLOAT\"}")));

        UUID tenantId = UUID.randomUUID();
        UUID loanId = UUID.randomUUID();
        String idempotencyKey = "loan-" + loanId;
        Instant before = Instant.now();

        publishLoanDisbursementRequested(
            loanDisbursementRequested(tenantId, idempotencyKey, loanId, "MPESA-0700000000", "20000.00", "TZS"));

        DisbursementInstruction instruction = disbursementRepository
            .findByIdempotencyKeyAndTenantId(idempotencyKey, tenantId)
            .orElseThrow(() -> new AssertionError("Expected a disbursement_instruction row for key " + idempotencyKey));
        assertThat(instruction.getStatus()).isEqualTo("FAILED");

        List<AuditLogEntry> auditRows = auditLogRepository.findByTenantIdAndEventTypeAndOccurredAtBetween(
            tenantId, "payment.DisbursementFailed", before.minusSeconds(5), Instant.now().plusSeconds(5));
        assertThat(auditRows).hasSize(1);
        JsonNode payload = objectMapper.readTree(auditRows.get(0).getPayload());
        assertThat(payload.path("reason").asText()).isEqualTo("INSUFFICIENT_FLOAT");

        wireMock.verify(exactly(1), postRequestedFor(urlPathEqualTo("/disburse")));
    }

    @Test
    void gateway500FailsTheInstructionWithGatewayUnavailableAndDoesNotRetry() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(serverError()));

        UUID tenantId = UUID.randomUUID();
        UUID loanId = UUID.randomUUID();
        String idempotencyKey = "loan-" + loanId;
        Instant before = Instant.now();

        publishLoanDisbursementRequested(
            loanDisbursementRequested(tenantId, idempotencyKey, loanId, "MPESA-0711111111", "30000.00", "TZS"));

        DisbursementInstruction instruction = disbursementRepository
            .findByIdempotencyKeyAndTenantId(idempotencyKey, tenantId)
            .orElseThrow(() -> new AssertionError("Expected a disbursement_instruction row for key " + idempotencyKey));
        assertThat(instruction.getStatus()).isEqualTo("FAILED");

        List<AuditLogEntry> auditRows = auditLogRepository.findByTenantIdAndEventTypeAndOccurredAtBetween(
            tenantId, "payment.DisbursementFailed", before.minusSeconds(5), Instant.now().plusSeconds(5));
        assertThat(auditRows).hasSize(1);
        JsonNode payload = objectMapper.readTree(auditRows.get(0).getPayload());
        assertThat(payload.path("reason").asText()).isEqualTo("GATEWAY_UNAVAILABLE");

        // No retry: exactly one attempt reached the rail despite the 500.
        wireMock.verify(exactly(1), postRequestedFor(urlPathEqualTo("/disburse")));
    }

    @Test
    void rejectedDisbursementWithNoReasonInGatewayResponseStillFailsAndPublishes() throws Exception {
        // Finding 1 regression: a rail that declines without populating its own "reason" field
        // must not NPE inside PaymentApiImpl.failDisbursement's Map.of(..., "reason", reason) --
        // that would roll back the phase-3 transaction and strand this row at PENDING forever,
        // with no *Failed event ever published for the requesting module to react to.
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson("{\"status\":\"REJECTED\"}")));

        UUID tenantId = UUID.randomUUID();
        UUID loanId = UUID.randomUUID();
        String idempotencyKey = "loan-" + loanId;
        Instant before = Instant.now();

        publishLoanDisbursementRequested(
            loanDisbursementRequested(tenantId, idempotencyKey, loanId, "MPESA-0733333333", "25000.00", "TZS"));

        DisbursementInstruction instruction = disbursementRepository
            .findByIdempotencyKeyAndTenantId(idempotencyKey, tenantId)
            .orElseThrow(() -> new AssertionError("Expected a disbursement_instruction row for key " + idempotencyKey));
        // The whole point: FAILED, not left at PENDING.
        assertThat(instruction.getStatus()).isEqualTo("FAILED");

        List<AuditLogEntry> auditRows = auditLogRepository.findByTenantIdAndEventTypeAndOccurredAtBetween(
            tenantId, "payment.DisbursementFailed", before.minusSeconds(5), Instant.now().plusSeconds(5));
        assertThat(auditRows).hasSize(1);
        JsonNode payload = objectMapper.readTree(auditRows.get(0).getPayload());
        assertThat(payload.path("reason").asText()).isEqualTo("GATEWAY_REJECTED");
    }

    @Test
    void acceptedResponseWithNoGatewayReferenceIsTreatedAsATransportFailureNotASuccess() throws Exception {
        // Finding 1's mirror case, worse because the rail may have actually moved money: an
        // ACCEPTED with nothing to reconcile against must never be persisted as COMPLETED.
        // MobileMoneyGatewayAdapter now throws GatewayException for this shape, which
        // PaymentRequestListener's existing catch(GatewayException) branch already turns into a
        // FAILED row plus a published event -- the same path scenario 3 (gateway 500) proves.
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson("{\"status\":\"ACCEPTED\"}")));

        UUID tenantId = UUID.randomUUID();
        UUID loanId = UUID.randomUUID();
        String idempotencyKey = "loan-" + loanId;
        Instant before = Instant.now();

        publishLoanDisbursementRequested(
            loanDisbursementRequested(tenantId, idempotencyKey, loanId, "MPESA-0744444444", "35000.00", "TZS"));

        DisbursementInstruction instruction = disbursementRepository
            .findByIdempotencyKeyAndTenantId(idempotencyKey, tenantId)
            .orElseThrow(() -> new AssertionError("Expected a disbursement_instruction row for key " + idempotencyKey));
        assertThat(instruction.getStatus()).isEqualTo("FAILED");
        assertThat(instruction.getGatewayReference()).isNull();

        List<AuditLogEntry> auditRows = auditLogRepository.findByTenantIdAndEventTypeAndOccurredAtBetween(
            tenantId, "payment.DisbursementFailed", before.minusSeconds(5), Instant.now().plusSeconds(5));
        assertThat(auditRows).hasSize(1);
        JsonNode payload = objectMapper.readTree(auditRows.get(0).getPayload());
        assertThat(payload.path("reason").asText()).isEqualTo("GATEWAY_UNAVAILABLE");

        // No retry, same invariant as the plain-500 scenario.
        wireMock.verify(exactly(1), postRequestedFor(urlPathEqualTo("/disburse")));
    }

    @Test
    void duplicateEventDoesNotDoubleClaimOrDoublePay() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"ACCEPTED\",\"gatewayReference\":\"MM-ONCE-ONLY\"}")));

        UUID tenantId = UUID.randomUUID();
        UUID loanId = UUID.randomUUID();
        String idempotencyKey = "loan-" + loanId;
        DomainEventEnvelope<Map<String, Object>> envelope =
            loanDisbursementRequested(tenantId, idempotencyKey, loanId, "MPESA-0722222222", "40000.00", "TZS");

        // The SAME envelope, published from two separate committed transactions -- a real
        // at-least-once redelivery, not merely two rows with the same idempotency key.
        publishLoanDisbursementRequested(envelope);
        publishLoanDisbursementRequested(envelope);

        assertThat(disbursementRepository.findAll().stream()
            .filter(d -> idempotencyKey.equals(d.getIdempotencyKey()) && tenantId.equals(d.getTenantId())))
            .hasSize(1);

        // The non-vacuous half of this test: the gateway itself was hit exactly once, so a
        // redelivery genuinely cannot cause a double payout -- not merely "a second row wasn't
        // written".
        wireMock.verify(exactly(1), postRequestedFor(urlPathEqualTo("/disburse")));
    }

    @Test
    void missingPayeeRefRecordsNothingAndNeverCallsTheGateway() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo("/disburse")).willReturn(okJson(
            "{\"status\":\"ACCEPTED\",\"gatewayReference\":\"MM-SHOULD-NOT-HAPPEN\"}")));

        UUID tenantId = UUID.randomUUID();
        UUID loanId = UUID.randomUUID();
        String idempotencyKey = "loan-" + loanId;

        publishLoanDisbursementRequested(
            loanDisbursementRequested(tenantId, idempotencyKey, loanId, null, "10000.00", "TZS"));

        Optional<DisbursementInstruction> instruction =
            disbursementRepository.findByIdempotencyKeyAndTenantId(idempotencyKey, tenantId);
        assertThat(instruction).isEmpty();

        wireMock.verify(exactly(0), postRequestedFor(urlPathEqualTo("/disburse")));
    }
}
