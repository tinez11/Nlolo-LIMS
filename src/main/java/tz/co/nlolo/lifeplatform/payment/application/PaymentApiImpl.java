package tz.co.nlolo.lifeplatform.payment.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.payment.api.*;
import tz.co.nlolo.lifeplatform.payment.domain.*;
import tz.co.nlolo.lifeplatform.payment.infrastructure.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
    // Task 8 (review fix, Critical 3): a fresh PROPAGATION_REQUIRES_NEW transaction for
    // applyGatewayCallback's mutation, opened only AFTER TenantContext has been set to the
    // resolved tenant. See applyGatewayCallback's javadoc for why this can't just be a plain
    // @Transactional on that method -- same root cause PaymentRequestListener's own class javadoc
    // already documents for the same reason (TenantAwareDataSource sets app.current_tenant_id at
    // connection-ACQUISITION time, so the connection a @Transactional method's own transaction
    // opens at method entry is bound before any TenantContext.set(...) inside that same method
    // body can affect it).
    private final TransactionTemplate requiresNewTransactionTemplate;

    public PaymentApiImpl(PaymentTransactionRepository paymentTransactionRepository,
                           DisbursementInstructionRepository disbursementRepository,
                           PayoutBatchRepository payoutBatchRepository,
                           PaymentIdempotencyRepository paymentIdempotencyRepository,
                           DisbursementIdempotencyRepository disbursementIdempotencyRepository,
                           ApplicationEventPublisher eventPublisher,
                           PlatformTransactionManager transactionManager) {
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.disbursementRepository = disbursementRepository;
        this.payoutBatchRepository = payoutBatchRepository;
        this.paymentIdempotencyRepository = paymentIdempotencyRepository;
        this.disbursementIdempotencyRepository = disbursementIdempotencyRepository;
        this.eventPublisher = eventPublisher;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
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

    /**
     * Review fix (I2): {@code gatewayReference} is now a real parameter, not hardcoded
     * {@code null}. A rail can decline WITH a reference attached (or, for the mobile-money
     * webhook path, the aggregator's reference is exactly what let the caller resolve this row's
     * tenant in the first place -- see {@code applyGatewayCallback}), and discarding it broke
     * that same callback's ability to resolve a REDELIVERED failure notification for the same
     * reference, since {@code resolve_disbursement_tenant} keys on this column. Existing callers
     * that genuinely have no reference (a transport failure before the rail ever responded) still
     * pass {@code null} explicitly, which is the correct value for that case, not a default this
     * method silently applied.
     */
    @Transactional
    void failDisbursement(UUID tenantId, UUID disbursementId, String gatewayReference, String reason) {
        DisbursementInstruction instruction = disbursementRepository
            .findByDisbursementIdAndTenantId(disbursementId, tenantId)
            .orElseThrow(() -> new PaymentNotFoundException("Disbursement " + disbursementId + " not found"));
        instruction.markFailed(gatewayReference);
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

    /** Review fix (I2): same reasoning as {@code failDisbursement} above, for the collection
     * ledger. */
    @Transactional
    void failCollection(UUID tenantId, UUID paymentTransactionId, String gatewayReference, String reason) {
        PaymentTransaction transaction = paymentTransactionRepository
            .findByPaymentTransactionIdAndTenantId(paymentTransactionId, tenantId)
            .orElseThrow(() -> new PaymentNotFoundException("Payment " + paymentTransactionId + " not found"));
        transaction.markFailed(gatewayReference);
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
     * @return {@link GatewayCallbackOutcome#APPLIED} if {@code gatewayReference} matched exactly
     *         one tenant's row in either ledger and the outcome was applied (including the
     *         idempotent no-op case of a redelivered, already-terminal outcome);
     *         {@link GatewayCallbackOutcome#AMBIGUOUS} if it matched rows in MORE THAN ONE
     *         tenant (a legitimate, expected occurrence -- see db-migrations/payment/V3 -- and
     *         deliberately NOT applied to any of them, never guessed);
     *         {@link GatewayCallbackOutcome#NOT_FOUND} if it matched nothing at all. The
     *         controller acks HTTP 200 in all three cases -- see
     *         {@code MobileMoneyCallbackController}'s own javadoc for why -- but logs (and, for
     *         AMBIGUOUS, alerts on) each outcome differently: collapsing AMBIGUOUS and NOT_FOUND
     *         into the same signal (Important finding 3) would make a real, working safety
     *         mechanism -- refusing to guess which tenant owns a shared reference -- read exactly
     *         like an ordinary data-entry error, with no way to notice a genuine payout sitting
     *         PENDING forever because of it.
     *
     * <p><b>Review fix (Critical 3): deliberately NOT {@code @Transactional} on this method.</b>
     * {@code TenantAwareDataSource} sets the {@code app.current_tenant_id} GUC only at JDBC
     * connection-ACQUISITION time. If this method carried a plain {@code @Transactional}, Spring
     * would open the transaction (and therefore acquire and bind the connection for the WHOLE
     * method body) at method entry -- before {@code TenantContext} has been set to anything, since
     * the very first thing this method does is the tenant-resolving read. Every later
     * {@code TenantContext.set(...)} inside {@code withResolvedTenant} would then be changing only
     * the ThreadLocal, with no effect on the connection already bound to that same transaction for
     * the rest of the method -- so the re-fetch via {@code findByGatewayReferenceAndTenantId} would
     * see zero rows under real RLS, always, exactly as {@code PaymentRequestListener}'s own class
     * javadoc already documents for the same underlying reason. The tenant-resolving read below
     * runs with no ambient transaction at all (a plain SELECT through a SECURITY DEFINER function
     * needs none), and the actual mutation runs inside a fresh {@code PROPAGATION_REQUIRES_NEW}
     * transaction opened by {@code requiresNewTransactionTemplate} from INSIDE
     * {@code withResolvedTenant}, i.e. strictly after {@code TenantContext.set(...)} -- so that
     * transaction's own connection acquisition sees the correct GUC from the start.
     */
    public GatewayCallbackOutcome applyGatewayCallback(String gatewayReference, boolean succeeded, String reason) {
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
                    failDisbursement(disbursementTenantId, disbursementId, gatewayReference, reasonOrDefault(reason));
                }
            });
            return GatewayCallbackOutcome.APPLIED;
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
                    failCollection(paymentTenantId, paymentTransactionId, gatewayReference, reasonOrDefault(reason));
                }
            });
            return GatewayCallbackOutcome.APPLIED;
        }
        // Neither ledger resolved to a single tenant. Distinguishing AMBIGUOUS from NOT_FOUND
        // needs one more narrow, SECURITY DEFINER-backed check per ledger -- only reached on this
        // rare "nothing resolved" path, never on the common case above.
        if (disbursementRepository.isGatewayReferenceAmbiguous(gatewayReference)
                || paymentTransactionRepository.isGatewayReferenceAmbiguous(gatewayReference)) {
            return GatewayCallbackOutcome.AMBIGUOUS;
        }
        return GatewayCallbackOutcome.NOT_FOUND;
    }

    /** Review fix (Important 3): distinguishes "a real cross-tenant collision on this
     * gateway_reference, correctly refused rather than guessed" from "this reference matched
     * nothing at all" -- the two were previously indistinguishable to the caller, which made a
     * working safety mechanism read like a data-entry error. See applyGatewayCallback's javadoc. */
    public enum GatewayCallbackOutcome { APPLIED, AMBIGUOUS, NOT_FOUND }

    private static String reasonOrDefault(String reason) {
        return reason != null ? reason : "GATEWAY_REPORTED_FAILURE";
    }

    /** Save/set/restore, not a bare set -- this runs synchronously on the webhook's own request
     * thread, which a container thread pool WILL reuse for an unrelated later request, so an
     * unconditional set-without-restore would leak this tenant onto whatever runs next on the
     * same thread. TenantContext is expected to be unset on entry (no bearer token was present
     * for this request), but restoring "whatever was there before" rather than unconditionally
     * clearing is the same defensive pattern PaymentRequestListener.withTenant already uses, for
     * the same reason.
     *
     * <p>Review fix (Critical 3): {@code action} now runs INSIDE a fresh
     * {@code PROPAGATION_REQUIRES_NEW} transaction opened here, i.e. after {@code TenantContext
     * .set(tenantId)} above -- not merely as a plain method call under whatever transaction (if
     * any) the caller already had. See {@code applyGatewayCallback}'s own javadoc for why opening
     * the transaction at the right moment, relative to setting TenantContext, is what actually
     * matters here, not merely setting TenantContext at all. */
    private void withResolvedTenant(UUID tenantId, Runnable action) {
        UUID previousTenant = TenantContext.getOrNull();
        TenantContext.set(tenantId);
        try {
            requiresNewTransactionTemplate.executeWithoutResult(status -> action.run());
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
