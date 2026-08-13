package tz.co.nlolo.lifeplatform.claims.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
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

    private void withTenant(DomainEventEnvelope<?> envelope, Consumer<Map<String, Object>> handler) {
        UUID previousTenant = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) envelope.payload();
            requiresNewTransactionTemplate.executeWithoutResult(status -> handler.accept(payload));
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

    private void handleCompleted(Map<String, Object> payload) {
        if (!"CLAIM_SETTLEMENT".equals(payload.get("purpose"))) {
            return; // another module's payout rode the same event type
        }
        UUID tenantId = TenantContext.get();
        UUID claimId = UUID.fromString((String) payload.get("sourceRef"));
        Claim claim = claimRepository.findByClaimIdAndTenantId(claimId, tenantId)
            .orElseThrow(() -> new IllegalStateException("Claim " + claimId + " not found for tenant " + tenantId));

        claim.markSettled();
        claimRepository.save(claim);
        eventPublisher.publishEvent(DomainEventEnvelope.of("claims.ClaimSettled", tenantId,
            Map.of("claimId", claimId, "settledAt", Instant.now().toString())));

        // A settled claim discharges the coverage, so the policy must leave ACTIVE -- otherwise
        // billing keeps generating premium invoices against it forever (the concrete bug this
        // milestone's user decision closed). Both PolicyApi methods are idempotent on repeat
        // (Task 2, Step 3), so a redelivered DisbursementCompleted cannot double-transition.
        if (claim.getClaimType() == ClaimType.MATURITY) {
            policyApi.markMatured(claim.getPolicyNumber(), "claims:" + claimId);
        } else {
            policyApi.terminateForSettledClaim(claim.getPolicyNumber(), claimId, "claims:" + claimId);
        }
    }

    private void handleFailed(Map<String, Object> payload) {
        if (!"CLAIM_SETTLEMENT".equals(payload.get("purpose"))) {
            return; // another module's payout rode the same event type
        }
        UUID tenantId = TenantContext.get();
        UUID claimId = UUID.fromString((String) payload.get("sourceRef"));
        Claim claim = claimRepository.findByClaimIdAndTenantId(claimId, tenantId)
            .orElseThrow(() -> new IllegalStateException("Claim " + claimId + " not found for tenant " + tenantId));

        // SETTLEMENT_REQUESTED -> APPROVED, reason preserved, staff retry worklist -- and,
        // deliberately, no policy call at all: this is a genuine rail decline, not an
        // indeterminate outcome (see this class's own javadoc), but it is still not evidence the
        // claim itself was wrong, only that this disbursement attempt failed.
        claim.markSettlementFailed((String) payload.get("reason"));
        claimRepository.save(claim);
        meterRegistry.counter(SETTLEMENT_FAILED_COUNTER).increment();
        log.error("Claim {} settlement disbursement FAILED for tenant {}: {}", claimId, tenantId, payload.get("reason"));
    }
}
