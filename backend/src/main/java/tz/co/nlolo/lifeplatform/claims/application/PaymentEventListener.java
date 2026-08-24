package tz.co.nlolo.lifeplatform.claims.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.claims.api.ClaimStatus;
import tz.co.nlolo.lifeplatform.claims.api.ClaimType;
import tz.co.nlolo.lifeplatform.claims.domain.Claim;
import tz.co.nlolo.lifeplatform.claims.infrastructure.ClaimRepository;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Closes M6's request/confirm loop on the claims side: consumes payment's
 * {@code DisbursementCompleted}/{@code DisbursementFailed} confirmation events
 * (docs/02-module-architecture.md:65) and drives {@code Claim.markSettled}/
 * {@code markSettlementFailed} accordingly, and -- on a genuine settlement, and ONLY then --
 * closes the underlying policy via {@code PolicyApi.markMatured}/{@code terminateForSettledClaim}
 * (user-approved decision 1: a settled claim discharges the coverage, so the policy must leave
 * ACTIVE, otherwise billing keeps generating premium invoices against it forever).
 *
 * <p>Mirrors {@code policyloan.application.PaymentEventListener}'s exact mechanics -- AFTER_COMMIT
 * (payment's own write must be durable first), a brand-new REQUIRES_NEW transaction via
 * {@link TransactionTemplate} (a plain REQUIRED call here would silently join the
 * already-committed producer transaction and never actually commit), and TenantContext
 * save/set/restore (this runs synchronously on the SAME thread as whatever committed payment's
 * transaction).
 *
 * <p><b>{@link #handleCompleted} runs in TWO separate transactions, and must keep doing so
 * (M6 final-review C1 -- a Critical confirmed empirically against real Postgres, not by
 * inspection).</b> Phase 1 commits the claim's own SETTLED transition plus the {@code ClaimSettled}
 * publish; phase 2, in its OWN transaction and its own try/catch, closes the policy. When all of it
 * shared ONE transaction, an {@code InvalidPolicyStateException} out of
 * {@code Policy.mature()}/{@code terminateForSettledClaim()} unwound the claim's SETTLED transition
 * too -- after the disbursement had already COMPLETED and the money had left. The observed end
 * state was: money moved, {@code gatewayReference} recorded, claim silently wedged back at
 * SETTLEMENT_REQUESTED with a NULL failure reason, no {@code ClaimSettled}, no counter, no alert,
 * indistinguishable from a genuine IN_DOUBT and unrecoverable through any existing transition. That
 * was reachable unattended, not as a corner case: {@code PolicyLapseRecommendedEventListener}
 * lapses a policy automatically at dunning level >= 5, and a deceased policyholder stops paying
 * premiums. The policy guards were widened for that case too (see {@code Policy.mature}'s javadoc),
 * but the phase separation is the part that must hold regardless: <b>a claims fact that money has
 * already made true can never be reverted by a foreign module's precondition.</b> This is the same
 * shape {@code payment.application.PaymentRequestListener} already uses -- commit phase 1, then do
 * the thing that can fail, then commit the outcome separately -- and the reason
 * {@link #withTenant} deliberately does NOT wrap its handler in a transaction of its own.
 *
 * <p><b>The residual case is signalled, not swallowed.</b> If phase 2 fails, the claim stays SETTLED
 * (correct -- the money moved) and the policy stays open, which means billing keeps invoicing it.
 * That is a real operational condition needing a human, so it increments
 * {@value #POLICY_CLOSURE_FAILED_COUNTER} and logs at ERROR naming both ids;
 * observability/alert-rules.yml carries the matching rule.
 *
 * <p><b>Documented limitation (M6 final-review I6).</b> Policy closure is one-way: {@code Policy}
 * has no transition out of SURRENDERED/MATURED ({@code reinstate()} requires LAPSED). Reopening a
 * SETTLED claim -- which {@code ClaimsApi.reopenClaim} deliberately allows -- therefore CANNOT
 * reverse the closure this listener performed, and the reopened claim can even be re-REJECTED while
 * its policy stays permanently closed. Adding a policy-reversal path is a later-milestone decision
 * (it needs its own event, its own audit story, and an answer for the premiums that were never
 * invoiced in between); it is recorded here rather than left for the next reader to rediscover.
 *
 * <p><b>Bean name is explicit.</b> {@code policyloan} and {@code billing} each already declare a
 * class named {@code PaymentEventListener}; a third unqualified {@code @Component} with the same
 * simple name is a bean-name collision that fails context startup the moment this class exists
 * alongside the other two (this exact bug cost a fix round earlier in this project). Hence
 * {@code @Component("claimsPaymentEventListener")}.
 *
 * <p><b>{@code DisbursementCompleted} and {@code DisbursementFailed} do NOT share a payload
 * shape.</b> Verified directly against {@code PaymentApiImpl}: {@code DisbursementCompleted}
 * carries {@code gatewayReference}, {@code amount}, and {@code completedAt}; {@code
 * DisbursementFailed} carries none of those, only {@code reason}. {@link #handleFailed} must not
 * assume otherwise.
 *
 * <p><b>{@code DisbursementFailed} means a genuine, definitive decline -- never "we do not
 * know."</b> {@code PaymentApiImpl.markDisbursementInDoubt}'s own javadoc records this danger for
 * exactly this event type: an indeterminate gateway outcome (a timeout, or an ACCEPTED response
 * with no reference to reconcile against) is recorded as {@code IN_DOUBT}, NOT as {@code FAILED},
 * specifically so that a real financial compensation -- here, leaving the claim at {@code APPROVED}
 * for a staff retry -- is never triggered for a payout that may actually have succeeded. So
 * {@link #handleFailed} only ever runs for a rail's real rejection, and never touches the policy;
 * only {@link #handleCompleted} (a genuine {@code DisbursementCompleted}) closes it.
 *
 * <p>There is deliberately no handler for an indeterminate rail outcome: payment's IN_DOUBT state
 * publishes NO event at all (api/asyncapi-events.yaml:571-592 records this explicitly and warns
 * against "completing" the file with an invented DisbursementInDoubt event). A claim can therefore
 * rest at SETTLEMENT_REQUESTED indefinitely. That is not fixable from claims -- payment already
 * alerts on it via lifeplatform_payment_in_doubt_total. Stated so this reads as a known boundary,
 * not a missed case.
 */
@Component("claimsPaymentEventListener")
public class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    /** Follows PaymentRequestListener's existing lifeplatform_payment_*_total naming convention,
     * adapted to this module: an operator needs to know a claim settlement failed and is now
     * sitting on the retry worklist, even though claims itself does not (and must not) retry it
     * automatically. */
    private static final String SETTLEMENT_FAILED_COUNTER = "lifeplatform_claims_settlement_failed_total";

    /** M6 final-review fix (C1, part 3), same naming convention as the counter above. The claim is
     * SETTLED and the money has moved, but the policy could not be closed -- so billing will keep
     * generating premium invoices against a policy whose claim has already paid out, and nothing
     * else anywhere signals that. A metric with no alert rule is half a fix (this project's M5
     * final-review I2), so observability/alert-rules.yml references this name directly. */
    private static final String POLICY_CLOSURE_FAILED_COUNTER = "lifeplatform_claims_policy_closure_failed_total";

    private final ClaimRepository claimRepository;
    private final PolicyApi policyApi;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public PaymentEventListener(ClaimRepository claimRepository, PolicyApi policyApi,
                                 ApplicationEventPublisher eventPublisher, MeterRegistry meterRegistry,
                                 PlatformTransactionManager transactionManager) {
        this.claimRepository = claimRepository;
        this.policyApi = policyApi;
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistry;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        switch (envelope.eventType()) {
            case "payment.DisbursementCompleted" -> withTenant(envelope, this::handleCompleted);
            case "payment.DisbursementFailed" -> withTenant(envelope, this::handleFailed);
            default -> { /* not claims-relevant */ }
        }
    }

    /**
     * TenantContext save/set/restore plus the catch-all, and deliberately NO transaction of its own
     * (M6 final-review C1): each handler below opens its own REQUIRES_NEW transaction(s) through
     * {@link #requiresNewTransactionTemplate}, so {@link #handleCompleted} can commit the claim's
     * SETTLED transition BEFORE attempting the policy closure that may legitimately fail. Wrapping
     * the handler here again would silently re-merge those phases into one transaction and
     * reintroduce the Critical this class's javadoc describes -- the claim's own settlement being
     * rolled back by a foreign module's precondition, after the money had already moved.
     */
    private void withTenant(DomainEventEnvelope<?> envelope, Consumer<Map<String, Object>> handler) {
        UUID previousTenant = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) envelope.payload();
            handler.accept(payload);
        } catch (Exception e) {
            log.error("claims failed to process {} for tenant {}", envelope.eventType(), envelope.tenantId(), e);
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }

    /** What phase 1 committed, carried into phase 2 so the latter needs no second read (the Claim
     * is detached once phase 1's transaction commits). {@code alreadySettled} is the redelivery
     * flag -- see {@link #handleCompleted}. */
    private record SettledClaimFacts(boolean alreadySettled, ClaimType claimType, String policyNumber) {}

    private void handleCompleted(Map<String, Object> payload) {
        if (!"CLAIM_SETTLEMENT".equals(payload.get("purpose"))) {
            return; // another module's payout rode the same event type
        }
        UUID tenantId = TenantContext.get();
        UUID claimId = UUID.fromString((String) payload.get("sourceRef"));

        // PHASE 1, its own committed transaction: the claim's own SETTLED fact plus the event that
        // announces it, atomically and unconditionally durable. Nothing a foreign module does may
        // undo either -- see this class's javadoc (final-review C1).
        SettledClaimFacts facts = requiresNewTransactionTemplate.execute(status -> {
            Claim claim = claimRepository.findByClaimIdAndTenantId(claimId, tenantId)
                .orElseThrow(() -> new IllegalStateException("Claim " + claimId + " not found for tenant " + tenantId));

            // Captured BEFORE the transition (M6 final-review I1). Claim.markSettled() is correctly
            // idempotent, but the publish below used to run unconditionally afterwards, so a
            // redelivered DisbursementCompleted emitted a SECOND claims.ClaimSettled -- confirmed
            // empirically (audit rows went 1 -> 2 on republishing the same envelope). Declared
            // consumers are finaccounting (journal posting), communication and regreporting: today's
            // blast radius is a duplicate audit row, tomorrow's is a double journal entry. Mirrors
            // PolicyApiImpl.markMatured/terminateForSettledClaim's own already-closed flags exactly,
            // so both sides of this hop suppress repeats the same way.
            boolean alreadySettled = claim.getStatus() == ClaimStatus.SETTLED;

            claim.markSettled();
            claimRepository.save(claim);
            if (!alreadySettled) {
                // M8: policyNumber and settledAmount added. Until now this event carried only
                // claimId + settledAt, so no consumer could attribute a settlement to a policy or
                // know what was paid -- reinsurance needs both to compute a claim recovery, and
                // finaccounting (M9) needs the amount for its journal posting. Both values are
                // already on the loaded Claim, so this is purely additive; the alternative
                // (triggering recovery from claims.ClaimApproved, which does carry them) was
                // rejected because approval precedes the rail and a settlement can still fail,
                // and a recoverable booked against money that never moved overstates assets.
                eventPublisher.publishEvent(DomainEventEnvelope.of("claims.ClaimSettled", tenantId,
                    Map.of("claimId", claimId,
                           "policyNumber", claim.getPolicyNumber(),
                           "settledAmount", Map.of("amount", claim.getApprovedAmount().toPlainString(),
                                                    "currencyCode", claim.getApprovedCurrency()),
                           "settledAt", Instant.now().toString())));
            }
            return new SettledClaimFacts(alreadySettled, claim.getClaimType(), claim.getPolicyNumber());
        });

        if (facts == null || facts.alreadySettled()) {
            // Redelivery: the claim was ALREADY SETTLED before this envelope arrived, so its policy
            // closure already ran (or already failed and was already alerted on). Skipping the
            // policy call as well as the publish keeps the two idempotent halves consistent.
            log.info("Ignoring redelivered DisbursementCompleted for already-SETTLED claim {} (tenant {})", claimId, tenantId);
            return;
        }

        // PHASE 2, a SEPARATE transaction with its own catch: a settled claim discharges the
        // coverage, so the policy must be closed -- otherwise billing keeps generating premium
        // invoices against it forever (the concrete bug this milestone's user decision closed).
        // Both PolicyApi methods are idempotent on repeat (Task 2, Step 3) and both now accept
        // LAPSED/SUSPENDED and treat either terminal status as satisfied (final-review C1 part 2),
        // so the legitimate cases no longer throw at all. The catch is for everything else --
        // PROPOSED, a vanished policy, a lock timeout -- where the ONLY correct outcome is to keep
        // the claim SETTLED and raise an alert, never to revert a completed payout.
        //
        // The REQUIRES_NEW template (rather than calling the @Transactional method bare) is required
        // here for the reason PaymentRequestListener's javadoc documents: at AFTER_COMMIT time a
        // plain REQUIRED @Transactional method does not open a real transaction.
        try {
            requiresNewTransactionTemplate.executeWithoutResult(status -> {
                if (facts.claimType() == ClaimType.MATURITY) {
                    policyApi.markMatured(facts.policyNumber(), "claims:" + claimId);
                } else {
                    policyApi.terminateForSettledClaim(facts.policyNumber(), claimId, "claims:" + claimId);
                }
            });
        } catch (RuntimeException e) {
            meterRegistry.counter(POLICY_CLOSURE_FAILED_COUNTER).increment();
            log.error("Claim {} is SETTLED and the disbursement COMPLETED (the money has moved), but policy {} "
                + "could NOT be closed for tenant {}. The claim is correct and final; the policy is still open, "
                + "so billing will keep invoicing it. Close the policy manually and reconcile the premium "
                + "invoices raised after the settlement date.", claimId, facts.policyNumber(), tenantId, e);
        }
    }

    private void handleFailed(Map<String, Object> payload) {
        if (!"CLAIM_SETTLEMENT".equals(payload.get("purpose"))) {
            return; // another module's payout rode the same event type
        }
        UUID tenantId = TenantContext.get();
        UUID claimId = UUID.fromString((String) payload.get("sourceRef"));

        // SETTLEMENT_REQUESTED -> APPROVED, reason preserved, staff retry worklist -- and,
        // deliberately, no policy call at all: this is a genuine rail decline, not an
        // indeterminate outcome (see this class's own javadoc), but it is still not evidence the
        // claim itself was wrong, only that this disbursement attempt failed. One transaction is
        // enough here precisely BECAUSE there is no foreign-module call to isolate.
        requiresNewTransactionTemplate.executeWithoutResult(status -> {
            Claim claim = claimRepository.findByClaimIdAndTenantId(claimId, tenantId)
                .orElseThrow(() -> new IllegalStateException("Claim " + claimId + " not found for tenant " + tenantId));
            claim.markSettlementFailed((String) payload.get("reason"));
            claimRepository.save(claim);
        });
        meterRegistry.counter(SETTLEMENT_FAILED_COUNTER).increment();
        log.error("Claim {} settlement disbursement FAILED for tenant {}: {}", claimId, tenantId, payload.get("reason"));
    }
}
