package tz.co.nlolo.lifeplatform.payment.application;

import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.payment.api.PaymentApi;
import tz.co.nlolo.lifeplatform.payment.api.PaymentNotFoundException;
import tz.co.nlolo.lifeplatform.payment.api.PayoutBatchView;
import tz.co.nlolo.lifeplatform.payment.domain.DisbursementInstruction;
import tz.co.nlolo.lifeplatform.payment.domain.PayoutBatch;
import tz.co.nlolo.lifeplatform.payment.infrastructure.DisbursementInstructionRepository;
import tz.co.nlolo.lifeplatform.payment.infrastructure.PayoutBatchRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Carries two of M5's three roadmap acceptance criteria: idempotent replay handling (the
 * claim-key registry, both single-tenant and cross-tenant halves), and PayoutBatch
 * partial-failure status derivation across all three reachable states.
 *
 * <p>Declared in {@code payment.application} (not {@code payment}, despite the module's
 * top-level package name) so it can call PaymentApiImpl's package-private transaction-boundary
 * methods directly -- those are deliberately package-private per the brief ("called only by
 * this module's own PaymentRequestListener, never by the REST layer"), and this is the same
 * access every future listener in this module will need.
 */
@SpringBootTest(classes = Application.class)
@Testcontainers
class PaymentApiIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void applyMigrations() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql",
            "db-migrations/payment/V1__create_payment_schema.sql",
            "db-migrations/payment/V2__grants_rls_money_checks_version_and_tenant_scoped_registries.sql",
            "db-migrations/payment/V3__inbound_callback_tenant_resolver.sql",
            "db-migrations/payment/V4__in_doubt_status_and_id_based_callback_resolvers.sql",
            "db-migrations/payment/V6__disbursement_method.sql",
            "db-migrations/payment/V7__q4_2026_partitions.sql");
    }

    @Autowired private PaymentApi paymentApi;
    @Autowired private PaymentApiImpl paymentApiImpl;
    @Autowired private DisbursementInstructionRepository disbursementRepository;
    @Autowired private PayoutBatchRepository payoutBatchRepository;

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    private void assignToBatch(UUID tenantId, UUID batchId, UUID... disbursementIds) {
        for (UUID disbursementId : disbursementIds) {
            DisbursementInstruction instruction = disbursementRepository
                .findByDisbursementIdAndTenantId(disbursementId, tenantId)
                .orElseThrow();
            instruction.assignToBatch(batchId);
            disbursementRepository.save(instruction);
        }
    }

    @Test
    void claimingTheSameKeyTwiceForOneTenantRecordsOneDisbursementAndDropsTheReplay() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);

        Optional<UUID> first = paymentApiImpl.recordDisbursementRequest(
            tenantId, "replay-key-1", "MPESA-0712345678", new BigDecimal("50000.00"), "TZS", "LOAN_DISBURSEMENT", "loan-1");
        Optional<UUID> second = paymentApiImpl.recordDisbursementRequest(
            tenantId, "replay-key-1", "MPESA-0712345678", new BigDecimal("50000.00"), "TZS", "LOAN_DISBURSEMENT", "loan-1");

        assertThat(first).isPresent();
        // The whole point: a redelivered event must NOT produce a second instruction, because
        // downstream that is a second real payout.
        assertThat(second).isEmpty();
        assertThat(disbursementRepository.findByIdempotencyKeyAndTenantId("replay-key-1", tenantId)).isPresent();
        assertThat(disbursementRepository.findAll().stream()
            .filter(d -> "replay-key-1".equals(d.getIdempotencyKey()) && tenantId.equals(d.getTenantId())))
            .hasSize(1);
    }

    /**
     * The cross-tenant half of the registry fix (payment/V2 section 3). Under V1's schema --
     * idempotency_key as a GLOBAL primary key with no tenant_id -- tenant B's genuine request
     * would be silently swallowed as tenant A's "duplicate". That is a lost payment across a
     * tenant boundary, and this is its regression test.
     */
    @Test
    void twoTenantsMayUseTheSameIdempotencyKeyIndependently() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        TenantContext.set(tenantA);
        Optional<UUID> forA = paymentApiImpl.recordDisbursementRequest(
            tenantA, "shared-key", "MPESA-0700000001", new BigDecimal("10000.00"), "TZS", "LOAN_DISBURSEMENT", "loan-a");
        TenantContext.set(tenantB);
        Optional<UUID> forB = paymentApiImpl.recordDisbursementRequest(
            tenantB, "shared-key", "MPESA-0700000002", new BigDecimal("20000.00"), "TZS", "LOAN_DISBURSEMENT", "loan-b");

        assertThat(forA).isPresent();
        assertThat(forB).isPresent();
        assertThat(forA).isNotEqualTo(forB);
    }

    /**
     * Roadmap acceptance criterion 3: PayoutBatch partial-failure handling. Instructions are
     * seeded and assigned to a batch directly -- no module produces a batched payout in M5 (see
     * Global Constraints) -- then the derived status and both counts are asserted across all
     * three reachable batch states.
     */
    @Test
    void payoutBatchStatusIsDerivedFromItsMembersAcrossAllThreeStates() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        PayoutBatch batch = payoutBatchRepository.save(new PayoutBatch(tenantId, "MATURITY_BATCH"));

        UUID one = paymentApiImpl.recordDisbursementRequest(tenantId, "batch-k1", "MPESA-0700000011",
            new BigDecimal("1000.00"), "TZS", "MATURITY_PAYOUT", "policy-1").orElseThrow();
        UUID two = paymentApiImpl.recordDisbursementRequest(tenantId, "batch-k2", "MPESA-0700000012",
            new BigDecimal("2000.00"), "TZS", "MATURITY_PAYOUT", "policy-2").orElseThrow();
        assignToBatch(tenantId, batch.getBatchId(), one, two);

        // Both still PENDING -> IN_PROGRESS, and COMPLETED would be a lie.
        PayoutBatchView inProgress = paymentApi.getPayoutBatch(batch.getBatchId());
        assertThat(inProgress.status()).isEqualTo("IN_PROGRESS");
        assertThat(inProgress.disbursementCount()).isEqualTo(2);
        assertThat(inProgress.failedCount()).isZero();

        // One succeeds, one fails, both terminal -> PARTIAL_FAILURE with a real failed count.
        paymentApiImpl.completeDisbursement(tenantId, one, "MM-OK-1");
        paymentApiImpl.failDisbursement(tenantId, two, null, "INSUFFICIENT_FLOAT");
        PayoutBatchView partial = paymentApi.getPayoutBatch(batch.getBatchId());
        assertThat(partial.status()).isEqualTo("PARTIAL_FAILURE");
        assertThat(partial.disbursementCount()).isEqualTo(2);
        assertThat(partial.failedCount()).isEqualTo(1);
        // The STORED column must also have been maintained, not just the derived read value --
        // this is what proves recomputeBatchStatusIfBatched actually ran. Without it the read
        // path would still return PARTIAL_FAILURE while the row sat at its IN_PROGRESS default,
        // and the assertion above alone could not tell the difference.
        assertThat(payoutBatchRepository.findByBatchIdAndTenantId(batch.getBatchId(), tenantId)
            .orElseThrow().getStatus()).isEqualTo("PARTIAL_FAILURE");

        // A batch whose every member COMPLETED is the only path to COMPLETED.
        UUID three = paymentApiImpl.recordDisbursementRequest(tenantId, "batch-k3", "MPESA-0700000013",
            new BigDecimal("3000.00"), "TZS", "MATURITY_PAYOUT", "policy-3").orElseThrow();
        PayoutBatch allGood = payoutBatchRepository.save(new PayoutBatch(tenantId, "COMMISSION_RUN"));
        assignToBatch(tenantId, allGood.getBatchId(), three);
        paymentApiImpl.completeDisbursement(tenantId, three, "MM-OK-3");
        assertThat(paymentApi.getPayoutBatch(allGood.getBatchId()).status()).isEqualTo("COMPLETED");
    }

    /** Regression for the final-review fix wave: {@code deriveStatus} originally treated
     * IN_DOUBT as terminal-and-not-FAILED, so a batch with an IN_DOUBT member alongside a
     * COMPLETED one misreported COMPLETED -- a lie if the in-doubt payout never lands. IN_DOUBT
     * must be treated the same as PENDING (neither is terminal), keeping the batch IN_PROGRESS
     * until every member actually resolves. */
    @Test
    void payoutBatchWithAnInDoubtMemberStaysInProgressNeverCompletedOrPartialFailure() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        PayoutBatch batch = payoutBatchRepository.save(new PayoutBatch(tenantId, "MATURITY_BATCH"));

        UUID resolved = paymentApiImpl.recordDisbursementRequest(tenantId, "batch-doubt-k1",
            "MPESA-0700000021", new BigDecimal("1000.00"), "TZS", "MATURITY_PAYOUT", "policy-doubt-1")
            .orElseThrow();
        UUID inDoubt = paymentApiImpl.recordDisbursementRequest(tenantId, "batch-doubt-k2",
            "MPESA-0700000022", new BigDecimal("2000.00"), "TZS", "MATURITY_PAYOUT", "policy-doubt-2")
            .orElseThrow();
        assignToBatch(tenantId, batch.getBatchId(), resolved, inDoubt);

        paymentApiImpl.completeDisbursement(tenantId, resolved, "MM-OK-DOUBT-1");
        paymentApiImpl.markDisbursementInDoubt(tenantId, inDoubt, null);

        PayoutBatchView view = paymentApi.getPayoutBatch(batch.getBatchId());
        assertThat(view.status()).isEqualTo("IN_PROGRESS");
        assertThat(view.failedCount()).isZero();
    }

    @Test
    void getDisbursementStatusThrowsForAnUnknownOrCrossTenantId() {
        UUID tenantId = UUID.randomUUID();
        UUID otherTenantId = UUID.randomUUID();
        TenantContext.set(tenantId);

        UUID disbursementId = paymentApiImpl.recordDisbursementRequest(tenantId, "lookup-key-1",
            "MPESA-0700000099", new BigDecimal("500.00"), "TZS", "LOAN_DISBURSEMENT", "loan-x").orElseThrow();

        // Missing row entirely.
        assertThatThrownBy(() -> paymentApi.getDisbursementStatus(UUID.randomUUID()))
            .isInstanceOf(PaymentNotFoundException.class);

        // A real row, but the caller is the wrong tenant -- proves the lookup is tenant-scoped,
        // not just id-scoped.
        TenantContext.set(otherTenantId);
        assertThatThrownBy(() -> paymentApi.getDisbursementStatus(disbursementId))
            .isInstanceOf(PaymentNotFoundException.class);
    }
}
