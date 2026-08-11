package tz.co.nlolo.lifeplatform.payment.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.payment.api.*;
import tz.co.nlolo.lifeplatform.payment.domain.*;
import tz.co.nlolo.lifeplatform.payment.infrastructure.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentApiImpl implements PaymentApi {

    private final PaymentTransactionRepository paymentTransactionRepository;
    private final DisbursementInstructionRepository disbursementRepository;
    private final PayoutBatchRepository payoutBatchRepository;
    private final PaymentIdempotencyRepository paymentIdempotencyRepository;
    private final DisbursementIdempotencyRepository disbursementIdempotencyRepository;
    private final ApplicationEventPublisher eventPublisher;

    public PaymentApiImpl(PaymentTransactionRepository paymentTransactionRepository,
                           DisbursementInstructionRepository disbursementRepository,
                           PayoutBatchRepository payoutBatchRepository,
                           PaymentIdempotencyRepository paymentIdempotencyRepository,
                           DisbursementIdempotencyRepository disbursementIdempotencyRepository,
                           ApplicationEventPublisher eventPublisher) {
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.disbursementRepository = disbursementRepository;
        this.payoutBatchRepository = payoutBatchRepository;
        this.paymentIdempotencyRepository = paymentIdempotencyRepository;
        this.disbursementIdempotencyRepository = disbursementIdempotencyRepository;
        this.eventPublisher = eventPublisher;
    }

    // ---- Read surface ----

    @Override
    public DisbursementStatusView getDisbursementStatus(UUID disbursementId) {
        UUID tenantId = TenantContext.get();
        return disbursementRepository.findByDisbursementIdAndTenantId(disbursementId, tenantId)
            .map(PaymentApiImpl::toView)
            .orElseThrow(() -> new PaymentNotFoundException("Disbursement " + disbursementId + " not found"));
    }

    @Override
    public PaymentStatusView getPaymentStatus(UUID paymentRequestId) {
        UUID tenantId = TenantContext.get();
        return paymentTransactionRepository.findByPaymentTransactionIdAndTenantId(paymentRequestId, tenantId)
            .map(PaymentApiImpl::toView)
            .orElseThrow(() -> new PaymentNotFoundException("Payment " + paymentRequestId + " not found"));
    }

    @Override
    public IdempotencyLookupResult getStatusByIdempotencyKey(String idempotencyKey) {
        UUID tenantId = TenantContext.get();
        Optional<PaymentTransaction> payment =
            paymentTransactionRepository.findByIdempotencyKeyAndTenantId(idempotencyKey, tenantId);
        if (payment.isPresent()) {
            return new IdempotencyLookupResult(toView(payment.get()), null);
        }
        return disbursementRepository.findByIdempotencyKeyAndTenantId(idempotencyKey, tenantId)
            .map(d -> new IdempotencyLookupResult(null, toView(d)))
            .orElseThrow(() -> new PaymentNotFoundException(
                "No payment or disbursement found for idempotency key " + idempotencyKey));
    }

    @Override
    public PayoutBatchView getPayoutBatch(UUID batchId) {
        UUID tenantId = TenantContext.get();
        PayoutBatch batch = payoutBatchRepository.findByBatchIdAndTenantId(batchId, tenantId)
            .orElseThrow(() -> new PaymentNotFoundException("Payout batch " + batchId + " not found"));
        List<DisbursementInstruction> members = disbursementRepository.findByBatchIdAndTenantId(batchId, tenantId);
        int failedCount = (int) members.stream().filter(m -> "FAILED".equals(m.getStatus())).count();
        // Derived on read, so the view can never disagree with the members even if a stored
        // status is stale. Deliverable 3 §7.3: "derived ... not duplicated".
        String derived = PayoutBatch.deriveStatus(members.stream().map(DisbursementInstruction::getStatus).toList());
        return new PayoutBatchView(batch.getBatchId(), batch.getBatchType(), derived, members.size(), failedCount);
    }

    // ---- Transaction boundary 1 of 3: record the request, claim the idempotency key.
    // Package-private: called only by this module's own PaymentRequestListener, never by the
    // REST layer and never by another module (payment's published API is read-only). ----

    /**
     * @return the new disbursement's id, or empty when this (tenant, key) pair was already
     *         claimed — meaning the event is a safe duplicate and MUST NOT reach the gateway.
     */
    @Transactional
    Optional<UUID> recordDisbursementRequest(UUID tenantId, String idempotencyKey, String payeeRef,
                                              BigDecimal amount, String currency, String purpose, String sourceRef) {
        DisbursementInstruction instruction =
            new DisbursementInstruction(tenantId, idempotencyKey, payeeRef, amount, currency, purpose, sourceRef);
        // Claim FIRST, and let the affected-row count decide. An INSERT ... ON CONFLICT DO
        // NOTHING returning 0 is the only race-free way to answer "did I already process this?"
        // -- a findBy...isPresent() check before inserting has a real TOCTOU window that two
        // concurrent redeliveries of the same event will hit, and here that window costs a
        // double payout.
        int claimed = disbursementIdempotencyRepository.claimKey(tenantId, idempotencyKey, instruction.getDisbursementId());
        if (claimed == 0) {
            return Optional.empty();
        }
        disbursementRepository.save(instruction);
        return Optional.of(instruction.getDisbursementId());
    }

    @Transactional
    Optional<UUID> recordCollectionRequest(UUID tenantId, String idempotencyKey, String payerRef,
                                            BigDecimal amount, String currency, String sourceRef) {
        PaymentTransaction transaction =
            new PaymentTransaction(tenantId, idempotencyKey, payerRef, amount, currency, sourceRef);
        int claimed = paymentIdempotencyRepository.claimKey(tenantId, idempotencyKey, transaction.getPaymentTransactionId());
        if (claimed == 0) {
            return Optional.empty();
        }
        paymentTransactionRepository.save(transaction);
        return Optional.of(transaction.getPaymentTransactionId());
    }

    // ---- Transaction boundaries 2 and 3: apply the gateway outcome and publish the
    // confirmation. Runs AFTER the HTTP call has returned, in a fresh transaction. ----

    @Transactional
    void completeDisbursement(UUID tenantId, UUID disbursementId, String gatewayReference) {
        DisbursementInstruction instruction = disbursementRepository
            .findByDisbursementIdAndTenantId(disbursementId, tenantId)
            .orElseThrow(() -> new PaymentNotFoundException("Disbursement " + disbursementId + " not found"));
        instruction.markCompleted(gatewayReference);
        disbursementRepository.save(instruction);
        recomputeBatchStatusIfBatched(tenantId, instruction);
        eventPublisher.publishEvent(DomainEventEnvelope.of("payment.DisbursementCompleted", tenantId,
            Map.of("disbursementId", disbursementId,
                   "idempotencyKey", instruction.getIdempotencyKey(),
                   "sourceRef", instruction.getSourceRef(),
                   "purpose", instruction.getPurpose(),
                   "gatewayReference", gatewayReference,
                   "amount", Map.of("amount", instruction.getAmount().toPlainString(),
                                    "currencyCode", instruction.getCurrency()),
                   "completedAt", Instant.now().toString())));
    }

    @Transactional
    void failDisbursement(UUID tenantId, UUID disbursementId, String reason) {
        DisbursementInstruction instruction = disbursementRepository
            .findByDisbursementIdAndTenantId(disbursementId, tenantId)
            .orElseThrow(() -> new PaymentNotFoundException("Disbursement " + disbursementId + " not found"));
        instruction.markFailed(null);
        disbursementRepository.save(instruction);
        recomputeBatchStatusIfBatched(tenantId, instruction);
        eventPublisher.publishEvent(DomainEventEnvelope.of("payment.DisbursementFailed", tenantId,
            Map.of("disbursementId", disbursementId,
                   "idempotencyKey", instruction.getIdempotencyKey(),
                   "sourceRef", instruction.getSourceRef(),
                   "purpose", instruction.getPurpose(),
                   "reason", reason)));
    }

    @Transactional
    void confirmCollection(UUID tenantId, UUID paymentTransactionId, String gatewayReference) {
        PaymentTransaction transaction = paymentTransactionRepository
            .findByPaymentTransactionIdAndTenantId(paymentTransactionId, tenantId)
            .orElseThrow(() -> new PaymentNotFoundException("Payment " + paymentTransactionId + " not found"));
        transaction.markConfirmed(gatewayReference);
        paymentTransactionRepository.save(transaction);
        eventPublisher.publishEvent(DomainEventEnvelope.of("payment.PaymentConfirmed", tenantId,
            Map.of("paymentRequestId", paymentTransactionId,
                   "idempotencyKey", transaction.getIdempotencyKey(),
                   "sourceRef", transaction.getSourceRef(),
                   "gatewayReference", gatewayReference,
                   "amount", Map.of("amount", transaction.getAmount().toPlainString(),
                                    "currencyCode", transaction.getCurrency()),
                   "confirmedAt", Instant.now().toString())));
    }

    @Transactional
    void failCollection(UUID tenantId, UUID paymentTransactionId, String reason) {
        PaymentTransaction transaction = paymentTransactionRepository
            .findByPaymentTransactionIdAndTenantId(paymentTransactionId, tenantId)
            .orElseThrow(() -> new PaymentNotFoundException("Payment " + paymentTransactionId + " not found"));
        transaction.markFailed(null);
        paymentTransactionRepository.save(transaction);
        eventPublisher.publishEvent(DomainEventEnvelope.of("payment.PaymentFailed", tenantId,
            Map.of("paymentRequestId", paymentTransactionId,
                   "idempotencyKey", transaction.getIdempotencyKey(),
                   "sourceRef", transaction.getSourceRef(),
                   "reason", reason)));
    }

    /**
     * Keeps payout_batch.status in step with its members whenever one reaches a terminal state.
     * Without this, PayoutBatch.recomputeStatus would be dead code and the stored column would
     * be permanently stuck at its IN_PROGRESS default — the read path re-derives (see
     * getPayoutBatch) so the API would still be correct, but a stored column that is always
     * wrong is a trap for the next reader and for any future direct SQL report.
     */
    private void recomputeBatchStatusIfBatched(UUID tenantId, DisbursementInstruction instruction) {
        UUID batchId = instruction.getBatchId();
        if (batchId == null) {
            return;
        }
        payoutBatchRepository.findByBatchIdAndTenantId(batchId, tenantId).ifPresent(batch -> {
            List<DisbursementInstruction> members = disbursementRepository.findByBatchIdAndTenantId(batchId, tenantId);
            batch.recomputeStatus(members.stream().map(DisbursementInstruction::getStatus).toList());
            payoutBatchRepository.save(batch);
        });
    }

    private static DisbursementStatusView toView(DisbursementInstruction d) {
        return new DisbursementStatusView(d.getDisbursementId(), d.getIdempotencyKey(),
            DisbursementStatus.valueOf(d.getStatus()), d.getAmount(), d.getCurrency(), d.getPurpose(),
            d.getGatewayReference(), d.getSourceRef(), d.getBatchId());
    }

    private static PaymentStatusView toView(PaymentTransaction p) {
        return new PaymentStatusView(p.getPaymentTransactionId(), p.getIdempotencyKey(),
            PaymentStatus.valueOf(p.getStatus()), p.getAmount(), p.getCurrency(),
            p.getGatewayReference(), p.getSourceRef());
    }
}
