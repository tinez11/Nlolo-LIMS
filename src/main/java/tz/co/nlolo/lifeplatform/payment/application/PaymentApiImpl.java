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
    // confirmation. Runs AFTER the HTTP call has returned, in a fresh transaction.
    // Package-private: called by this module's own PaymentRequestListener (same package) and,
    // as of Task 8, by applyGatewayCallback below (same class) -- never directly by the REST
    // layer or another module. See applyGatewayCallback's javadoc for why the callback
    // controller reaches these THROUGH that one new public method rather than each becoming
    // public in its own right. ----

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

    // ---- Task 8: the mobile-money gateway's inbound callback entry point
    // (payment.infrastructure.MobileMoneyCallbackController). PUBLIC and deliberately NOT part of
    // payment.api.PaymentApi -- that interface stays exactly as documented, "read-only ... to
    // everything except its own internal event listeners" (PaymentApi's own javadoc), because
    // that statement is about OTHER MODULES calling payment, not about payment's own REST layer
    // handling its own external ACL boundary (openapi-payment.yaml's own words: "the only
    // externally-facing concern is checking status and receiving gateway callbacks"). Adding a
    // write method to PaymentApi would leak this capability to any future module depending on
    // payment::api; keeping it here, public only on the concrete impl, does not. This mirrors how
    // PaymentRequestListener (same package) already reaches the four completion methods above --
    // this is simply the same "concrete impl, not the interface" pattern extended to a caller
    // that sits in a different package (payment.infrastructure), which is why this one method is
    // public while completeDisbursement/failDisbursement/confirmCollection/failCollection stay
    // package-private: everything the callback needs is orchestrated in one place instead of
    // requiring the controller to reach into four narrower entry points plus repositories
    // directly. ----

    /**
     * Resolves which tenant owns {@code gatewayReference}, then applies the aggregator's
     * reported outcome under that tenant's ordinary RLS-enforced path.
     *
     * <p>The callback carries no bearer token (authenticated by MobileMoneyHmacFilter's HMAC
     * signature instead), so TenantContextFilter never runs for it and TenantContext is unset on
     * entry -- there is no tenant to scope an ordinary query with yet. Resolution instead goes
     * through {@code resolveTenantByGatewayReference}, a narrow, PK-style lookup backed by a
     * SECURITY DEFINER function (db-migrations/payment/V3): payment's RLS policies are
     * fail-closed (an unset {@code app.current_tenant_id} hides every row, not just leaves them
     * unfiltered), so an ordinary unscoped SELECT would see nothing even for a genuinely matching
     * row. See that migration's header comment for the full mechanism and why it is a deliberate,
     * narrowly-scoped exception rather than a general bypass. Once resolved, TenantContext is set
     * and every subsequent read/write -- including the re-fetch immediately below -- runs through
     * the normal tenant-scoped, RLS-enforced repository methods; this method is a bootstrap, not
     * a replacement for RLS.
     *
     * <p>Checks the disbursement ledger before the collection ledger; the two are not expected to
     * share a gateway_reference namespace in practice, but if one somehow did, disbursement wins
     * deterministically rather than leaving the outcome to query order.
     *
     * @return true if {@code gatewayReference} matched a row in either ledger and the outcome
     *         was applied (including the idempotent no-op case of a redelivered, already-terminal
     *         outcome); false if it matched nothing in either ledger. The controller acks HTTP 200
     *         either way -- see {@code MobileMoneyCallbackController}'s own javadoc for why.
     */
    @Transactional
    public boolean applyGatewayCallback(String gatewayReference, boolean succeeded, String reason) {
        UUID disbursementTenantId = disbursementRepository.resolveTenantByGatewayReference(gatewayReference);
        if (disbursementTenantId != null) {
            withResolvedTenant(disbursementTenantId, () -> {
                UUID disbursementId = disbursementRepository
                    .findByGatewayReferenceAndTenantId(gatewayReference, disbursementTenantId)
                    .map(DisbursementInstruction::getDisbursementId)
                    .orElseThrow(() -> new PaymentNotFoundException(
                        "Disbursement with gatewayReference " + gatewayReference + " vanished after tenant resolution"));
                if (succeeded) {
                    completeDisbursement(disbursementTenantId, disbursementId, gatewayReference);
                } else {
                    failDisbursement(disbursementTenantId, disbursementId, reasonOrDefault(reason));
                }
            });
            return true;
        }
        UUID paymentTenantId = paymentTransactionRepository.resolveTenantByGatewayReference(gatewayReference);
        if (paymentTenantId != null) {
            withResolvedTenant(paymentTenantId, () -> {
                UUID paymentTransactionId = paymentTransactionRepository
                    .findByGatewayReferenceAndTenantId(gatewayReference, paymentTenantId)
                    .map(PaymentTransaction::getPaymentTransactionId)
                    .orElseThrow(() -> new PaymentNotFoundException(
                        "Payment with gatewayReference " + gatewayReference + " vanished after tenant resolution"));
                if (succeeded) {
                    confirmCollection(paymentTenantId, paymentTransactionId, gatewayReference);
                } else {
                    failCollection(paymentTenantId, paymentTransactionId, reasonOrDefault(reason));
                }
            });
            return true;
        }
        return false;
    }

    private static String reasonOrDefault(String reason) {
        return reason != null ? reason : "GATEWAY_REPORTED_FAILURE";
    }

    /** Save/set/restore, not a bare set -- this runs synchronously on the webhook's own request
     * thread, which a container thread pool WILL reuse for an unrelated later request, so an
     * unconditional set-without-restore would leak this tenant onto whatever runs next on the
     * same thread. TenantContext is expected to be unset on entry (no bearer token was present
     * for this request), but restoring "whatever was there before" rather than unconditionally
     * clearing is the same defensive pattern PaymentRequestListener.withTenant already uses, for
     * the same reason. */
    private void withResolvedTenant(UUID tenantId, Runnable action) {
        UUID previousTenant = TenantContext.getOrNull();
        TenantContext.set(tenantId);
        try {
            action.run();
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
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
