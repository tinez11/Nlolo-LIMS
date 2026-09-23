package tz.co.nlolo.lifeplatform.payment.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.payment.api.*;
import tz.co.nlolo.lifeplatform.payment.domain.*;
import tz.co.nlolo.lifeplatform.payment.infrastructure.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(PaymentApiImpl.class);

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
        return recordDisbursementRequest(tenantId, idempotencyKey, payeeRef, amount, currency,
            purpose, sourceRef, DisbursementMethod.MOBILE_MONEY);
    }

    /**
     * @param method MOBILE_MONEY goes to the gateway, as every disbursement on this platform
     *     always has. EFT is recorded AWAITING_EXECUTION and never handed to a rail at all —
     *     finance moves it in the bank's own portal and confirms it afterwards.
     */
    @Transactional
    Optional<UUID> recordDisbursementRequest(UUID tenantId, String idempotencyKey, String payeeRef,
                                              BigDecimal amount, String currency, String purpose,
                                              String sourceRef, DisbursementMethod method) {
        DisbursementInstruction instruction = new DisbursementInstruction(
            tenantId, idempotencyKey, payeeRef, amount, currency, purpose, sourceRef, method);
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
        if (method == DisbursementMethod.EFT) {
            // An EFT sits unpaid for days while a person finds time to visit the bank. That window
            // is the whole reason this event exists: for the mobile-money rail the gap between
            // "approved" and "paid" is milliseconds and the books lose nothing by recognising the
            // expense only on payment, but a multi-million-shilling obligation that is invisible in
            // the general ledger for a week is a real misstatement. finaccounting books
            // DR Claims Expense / CR Claims Payable here and reverses it on execution, where the
            // existing claims.ClaimSettled rule then books the expense against cash.
            eventPublisher.publishEvent(DomainEventEnvelope.of("payment.EftDisbursementAwaitingExecution", tenantId,
                Map.of("disbursementId", instruction.getDisbursementId(),
                       "purpose", purpose,
                       "sourceRef", sourceRef,
                       "amount", Map.of("amount", amount.toPlainString(), "currencyCode", currency))));
        }
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

    @Override
    @Transactional(readOnly = true)
    public List<DisbursementStatusView> listAwaitingEftExecution() {
        return disbursementRepository
            .findByTenantIdAndStatusOrderByCreatedAtAsc(TenantContext.get(), "AWAITING_EXECUTION")
            .stream().map(PaymentApiImpl::toView).toList();
    }

    /**
     * The EFT rail's completion, and the only one it has. There is no callback and no gateway to
     * hear from: a person in finance moved the money in the bank's portal and is recording that
     * they did, with the bank's own reference.
     *
     * <p><b>Public, unlike every other write on this class</b>, for the same reason
     * {@code applyGatewayCallback} is: the trigger is external to the platform. A mobile-money
     * transfer is confirmed by the aggregator calling {@code MobileMoneyCallbackController}; an
     * EFT is confirmed by a finance officer calling {@link
     * tz.co.nlolo.lifeplatform.payment.infrastructure.DisbursementController}. Both reach this
     * class from payment's own infrastructure package, never from another module — payment's
     * published {@link tz.co.nlolo.lifeplatform.payment.api.PaymentApi} stays read-only.
     *
     * <p>It publishes {@code payment.DisbursementCompleted} with the identical payload shape
     * {@link #completeDisbursement} publishes, deliberately: every downstream consumer
     * (claims, distribution, policyloan, finaccounting) then treats an executed EFT exactly as it
     * treats a completed mobile-money payout, with no rail-awareness anywhere but here. The bank
     * reference travels in {@code gatewayReference} because it answers the same reconciliation
     * question.
     *
     * @throws PaymentNotFoundException if no such disbursement exists for this tenant
     * @throws IllegalStateException if the row is not AWAITING_EXECUTION — which is what a
     *         mobile-money row is not, so this also refuses to let anyone hand-complete a payout
     *         that belongs to the gateway
     */
    @Transactional
    public void markEftExecuted(UUID disbursementId, String bankReference, String executedBy) {
        UUID tenantId = TenantContext.get();
        DisbursementInstruction instruction = disbursementRepository
            .findByDisbursementIdAndTenantId(disbursementId, tenantId)
            .orElseThrow(() -> new PaymentNotFoundException("Disbursement " + disbursementId + " not found"));
        boolean alreadyCompleted = "COMPLETED".equals(instruction.getStatus());
        instruction.markEftExecuted(bankReference, executedBy);
        if (alreadyCompleted) {
            // markEftExecuted is idempotent on a COMPLETED row, and so is this method: republishing
            // DisbursementCompleted would drive a second ClaimSettled downstream. Claims guards
            // that itself, but relying on a consumer's guard to make a producer safe is how the
            // duplicate-settlement bug documented in claims.PaymentEventListener happened.
            log.info("Disbursement {} is already COMPLETED -- treating the repeat EFT confirmation as "
                + "a no-op and publishing nothing", disbursementId);
            return;
        }
        disbursementRepository.save(instruction);
        recomputeBatchStatusIfBatched(tenantId, instruction);
        // Reverses the accrual raised when this EFT was instructed, so the expense is recognised
        // exactly once: the DisbursementCompleted below drives claims.ClaimSettled, whose existing
        // rule books DR Claims Expense / CR Cash. Published BEFORE it only for readability -- both
        // are AFTER_COMMIT and finaccounting posts each as its own independent entry.
        eventPublisher.publishEvent(DomainEventEnvelope.of("payment.EftDisbursementExecuted", tenantId,
            Map.of("disbursementId", disbursementId,
                   "purpose", instruction.getPurpose(),
                   "sourceRef", instruction.getSourceRef(),
                   "amount", Map.of("amount", instruction.getAmount().toPlainString(),
                                    "currencyCode", instruction.getCurrency()))));
        eventPublisher.publishEvent(DomainEventEnvelope.of("payment.DisbursementCompleted", tenantId,
            Map.of("disbursementId", disbursementId,
                   "idempotencyKey", instruction.getIdempotencyKey(),
                   "sourceRef", instruction.getSourceRef(),
                   "purpose", instruction.getPurpose(),
                   "gatewayReference", bankReference,
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

    /**
     * Review fix (C2): records an INDETERMINATE gateway outcome -- a read timeout, or an ACCEPTED
     * response with no {@code gatewayReference} to reconcile against -- as {@code IN_DOUBT} rather
     * than as definitive {@code FAILED}.
     *
     * <p><b>Deliberately publishes NO event.</b> That is the entire point, not an oversight.
     * {@code payment.DisbursementFailed} is consumed by
     * {@code policyloan.application.PaymentEventListener.handleFailed}, which calls
     * {@code PolicyLoanApiImpl.markDisbursementFailed} -- writing a {@code REVERSAL} loan
     * transaction and calling {@code policyApi.releaseEncumbrance(...)}. Those are real financial
     * compensations, correct for "the rail definitely did not pay" and WRONG for "we do not know":
     * if the rail actually paid, the platform has just handed the policyholder's loan value back
     * while the money is gone. Publishing nothing leaves the loan at
     * {@code DISBURSEMENT_REQUESTED} with its encumbrance intact -- verified, not assumed:
     * {@code PaymentEventListener}'s switch has exactly two payment cases
     * ({@code DisbursementCompleted}, {@code DisbursementFailed}) and a {@code default} no-op, so
     * an unpublished event is a genuine no-op on the policyloan side rather than an implicit
     * failure path.
     *
     * <p>Because publishing nothing would otherwise make this state INVISIBLE (the requesting
     * module hears nothing and no dashboard moves), the alertable signal is the caller's
     * responsibility and is not optional: {@code PaymentRequestListener} increments
     * {@code lifeplatform_payment_in_doubt_total} and logs at ERROR naming the id, and
     * observability/alert-rules.yml alerts on that counter. The durable record is the IN_DOUBT row
     * itself, which reconciliation can find precisely because it is not mixed in with PENDING
     * ("never attempted") or FAILED ("definitely did not pay").
     *
     * <p>Idempotent and non-destructive by way of {@link DisbursementInstruction#markInDoubt}:
     * safe on a repeat, and it refuses to walk a row back out of a terminal state.
     */
    @Transactional
    void markDisbursementInDoubt(UUID tenantId, UUID disbursementId, String gatewayReference) {
        DisbursementInstruction instruction = disbursementRepository
            .findByDisbursementIdAndTenantId(disbursementId, tenantId)
            .orElseThrow(() -> new PaymentNotFoundException("Disbursement " + disbursementId + " not found"));
        instruction.markInDoubt(gatewayReference);
        disbursementRepository.save(instruction);
        // No recomputeBatchStatusIfBatched call, deliberately: IN_DOUBT is not terminal, so the
        // batch's derived status is unchanged (still IN_PROGRESS) -- see V4's note on why
        // payout_batch.status is not widened.
    }

    /** Review fix (C2): the collection-ledger equivalent of {@link #markDisbursementInDoubt} --
     * same reasoning, same "publish nothing, signal via counter + ERROR log" posture. On this side
     * the un-published event is {@code payment.PaymentFailed}, which
     * {@code billing.application.PaymentEventListener} consumes; not publishing it leaves the
     * invoice in whatever state it was already in (DUE/PARTIALLY_PAID) rather than recording a
     * definitive collection failure the platform cannot actually vouch for. */
    @Transactional
    void markCollectionInDoubt(UUID tenantId, UUID paymentTransactionId, String gatewayReference) {
        PaymentTransaction transaction = paymentTransactionRepository
            .findByPaymentTransactionIdAndTenantId(paymentTransactionId, tenantId)
            .orElseThrow(() -> new PaymentNotFoundException("Payment " + paymentTransactionId + " not found"));
        transaction.markInDoubt(gatewayReference);
        paymentTransactionRepository.save(transaction);
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
     * <p><b>Review fix (C1): a SECOND resolution route, keyed on our own merchant reference.</b>
     * The gateway_reference route above cannot possibly work for the rows most likely to need
     * webhook recovery. When the rail never responded (a read timeout) or responded ACCEPTED
     * without a reference, {@code gateway_reference} is NULL on that row -- forever -- and V2's
     * supporting indexes are literally {@code WHERE gateway_reference IS NOT NULL}. So the
     * aggregator's later async notification for a payout that DID go through resolved to nothing,
     * was logged at WARN, and was acked 200: unrecoverable by any callback, ever. C2's IN_DOUBT
     * rows are exactly that population.
     *
     * <p>{@code merchantReference} closes it. It is the reference THIS PLATFORM generated and sent
     * to the rail ({@code PaymentRequestListener} passes {@code disbursementId.toString()} /
     * {@code transactionId.toString()} as the outbound request's {@code reference}), echoed back by
     * the aggregator in the callback's {@code reference} field -- previously used for log lines
     * only. It is tried ONLY after the gateway_reference route has resolved to nothing, and
     * critically, only after the AMBIGUOUS check: a genuine cross-tenant collision on
     * {@code gateway_reference} must still surface as AMBIGUOUS with nothing mutated, never be
     * masked by quietly succeeding through the other route on a reference the caller also supplied.
     * When the fallback does apply, it writes the aggregator's {@code gatewayReference} onto the
     * row (via the ordinary {@code markCompleted}/{@code markFailed} path), which REPAIRS the null
     * so any subsequent redelivery resolves through the primary route as normal.
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
    public GatewayCallbackOutcome applyGatewayCallback(String gatewayReference, String merchantReference,
                                                       boolean succeeded, String reason) {
        UUID disbursementTenantId = disbursementRepository.resolveTenantByGatewayReference(gatewayReference);
        if (disbursementTenantId != null) {
            withResolvedTenant(disbursementTenantId, () -> {
                UUID disbursementId = disbursementRepository
                    .findByGatewayReferenceAndTenantId(gatewayReference, disbursementTenantId)
                    .map(DisbursementInstruction::getDisbursementId)
                    .orElseThrow(() -> new PaymentNotFoundException(
                        "Disbursement with gatewayReference " + gatewayReference + " vanished after tenant resolution"));
                applyToDisbursement(disbursementTenantId, disbursementId, gatewayReference, succeeded, reason);
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
                applyToCollection(paymentTenantId, paymentTransactionId, gatewayReference, succeeded, reason);
            });
            return GatewayCallbackOutcome.APPLIED;
        }
        // Neither ledger resolved to a single tenant. Distinguishing AMBIGUOUS from NOT_FOUND
        // needs one more narrow, SECURITY DEFINER-backed check per ledger -- only reached on this
        // rare "nothing resolved" path, never on the common case above.
        //
        // This check comes BEFORE the C1 fallback below, deliberately and load-bearingly: an
        // AMBIGUOUS gateway_reference is a real cross-tenant collision that this platform refuses
        // to guess on, and it must keep surfacing as AMBIGUOUS (nothing mutated, alertable counter
        // incremented) rather than being quietly "resolved" through the other route. Otherwise the
        // fallback would silently mask the exact safety mechanism V3's Critical-5 fix installed.
        if (disbursementRepository.isGatewayReferenceAmbiguous(gatewayReference)
                || paymentTransactionRepository.isGatewayReferenceAmbiguous(gatewayReference)) {
            return GatewayCallbackOutcome.AMBIGUOUS;
        }
        return applyByMerchantReference(merchantReference, gatewayReference, succeeded, reason);
    }

    /**
     * Review fix (C1): the fallback route. Resolves the row by the merchant reference WE generated
     * and the aggregator echoed back, which is the only handle that exists for a row whose
     * {@code gateway_reference} is NULL (transport failure / ACCEPTED-without-reference -- i.e.
     * C2's IN_DOUBT population, the rows most likely to need exactly this).
     *
     * <p>A {@code reference} that is absent or not a UUID is simply NOT_FOUND, not an error: the
     * field is aggregator-controlled free text (its DTO does not constrain it) and this platform
     * must not throw on input it did not generate. That is a stricter posture than it looks -- an
     * unparseable reference means we have no handle at all, which is precisely "not found".
     */
    private GatewayCallbackOutcome applyByMerchantReference(String merchantReference, String gatewayReference,
                                                            boolean succeeded, String reason) {
        UUID ourOwnId = parseUuidOrNull(merchantReference);
        if (ourOwnId == null) {
            return GatewayCallbackOutcome.NOT_FOUND;
        }
        UUID disbursementTenantId = disbursementRepository.resolveTenantByDisbursementId(ourOwnId);
        if (disbursementTenantId != null) {
            // No COUNT(DISTINCT) / ambiguity check needed on this route, and none is possible:
            // ourOwnId is a component of the ledger's PRIMARY KEY, so at most one row (and
            // therefore at most one tenant) can match. See V4's section-2 comment.
            withResolvedTenant(disbursementTenantId, () ->
                applyToDisbursement(disbursementTenantId, ourOwnId, gatewayReference, succeeded, reason));
            return GatewayCallbackOutcome.APPLIED;
        }
        UUID paymentTenantId = paymentTransactionRepository.resolveTenantByPaymentTransactionId(ourOwnId);
        if (paymentTenantId != null) {
            withResolvedTenant(paymentTenantId, () ->
                applyToCollection(paymentTenantId, ourOwnId, gatewayReference, succeeded, reason));
            return GatewayCallbackOutcome.APPLIED;
        }
        return GatewayCallbackOutcome.NOT_FOUND;
    }

    /** Shared by both resolution routes so the two can never drift into applying different
     * outcomes for the same reported result. Reached only from inside
     * {@code withResolvedTenant}, i.e. with TenantContext set and a fresh REQUIRES_NEW transaction
     * already open -- see applyGatewayCallback's javadoc on why that ordering is load-bearing. */
    private void applyToDisbursement(UUID tenantId, UUID disbursementId, String gatewayReference,
                                      boolean succeeded, String reason) {
        if (succeeded) {
            completeDisbursement(tenantId, disbursementId, gatewayReference);
        } else {
            failDisbursement(tenantId, disbursementId, gatewayReference, reasonOrDefault(reason));
        }
    }

    private void applyToCollection(UUID tenantId, UUID paymentTransactionId, String gatewayReference,
                                    boolean succeeded, String reason) {
        if (succeeded) {
            confirmCollection(tenantId, paymentTransactionId, gatewayReference);
        } else {
            failCollection(tenantId, paymentTransactionId, gatewayReference, reasonOrDefault(reason));
        }
    }

    private static UUID parseUuidOrNull(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(candidate.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
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
            d.getGatewayReference(), d.getSourceRef(), d.getBatchId(),
            d.getPayeeRef(), d.getCreatedAt());
    }

    private static PaymentStatusView toView(PaymentTransaction p) {
        return new PaymentStatusView(p.getPaymentTransactionId(), p.getIdempotencyKey(),
            PaymentStatus.valueOf(p.getStatus()), p.getAmount(), p.getCurrency(),
            p.getGatewayReference(), p.getSourceRef());
    }
}
