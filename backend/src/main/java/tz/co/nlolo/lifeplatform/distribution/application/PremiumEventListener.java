package tz.co.nlolo.lifeplatform.distribution.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.distribution.api.TierType;
import tz.co.nlolo.lifeplatform.distribution.domain.CommissionAccrual;
import tz.co.nlolo.lifeplatform.distribution.domain.CommissionCalculator;
import tz.co.nlolo.lifeplatform.distribution.domain.PolicyProjection;
import tz.co.nlolo.lifeplatform.distribution.infrastructure.PolicyProjectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Task 7: consumes {@code billing.PremiumCollected} and accrues RENEWAL-tier commission. The
 * companion to {@link PolicyEventListener}, which accrues FIRST_YEAR at issuance -- together they
 * are the two triggers Task 4's {@link CommissionCalculator} recognises.
 *
 * <p>{@code billing} is not in distribution's {@code allowedDependencies} either, so as with
 * policy's events everything here comes from the event payload plus this module's own {@code
 * policy_projection}. {@code billing.PremiumCollected} is new in M7 and exists precisely because
 * {@code payment.PaymentConfirmed} carries only {@code sourceRef = invoiceId} with no
 * {@code policyNumber}, leaving nothing to attribute a collection to a policy -- and therefore to
 * an agent -- without a second hop through {@code billing}.
 *
 * <p>Mechanics are {@link PolicyEventListener}'s, for the same reasons documented at length there:
 * {@code AFTER_COMMIT} (billing's invoice must be durably PAID before commission follows from it),
 * one {@code PROPAGATION_REQUIRES_NEW} {@link TransactionTemplate} (a plain {@code @Transactional}
 * called from an AFTER_COMMIT callback silently joins the already-committed producer transaction
 * and never commits), {@code TenantContext} save/set/restore rather than an unconditional clear,
 * and ONE local transaction per handler because nothing here calls into another module.
 *
 * <p><b>The producer only ever fires on the transition INTO PAID</b> (see {@code
 * BillingApiImpl.applyConfirmedPayment}), so this listener never sees a partial collection and
 * never sees a no-op payment against an already-PAID or WAIVED invoice. It can therefore treat
 * every event it receives as "this invoice's premium is fully collected", and rely on {@code
 * invoiceId} being a sound idempotency key.
 */
@Component("distributionPremiumEventListener")
public class PremiumEventListener {

    private static final Logger log = LoggerFactory.getLogger(PremiumEventListener.class);

    private final PolicyProjectionRepository policyProjectionRepository;
    private final DistributionApiImpl distributionApiImpl;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public PremiumEventListener(PolicyProjectionRepository policyProjectionRepository,
                                 DistributionApiImpl distributionApiImpl,
                                 ApplicationEventPublisher eventPublisher,
                                 PlatformTransactionManager transactionManager) {
        this.policyProjectionRepository = policyProjectionRepository;
        this.distributionApiImpl = distributionApiImpl;
        this.eventPublisher = eventPublisher;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        switch (envelope.eventType()) {
            case "billing.PremiumCollected" -> withTenant(envelope, this::handlePremiumCollected);
            default -> { /* not distribution-relevant */ }
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
            log.error("distribution failed to process {} for tenant {}", envelope.eventType(), envelope.tenantId(), e);
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }

    /**
     * <b>The first-invoice guard is the load-bearing logic here.</b> Issuance already paid
     * FIRST_YEAR on this policy's premium; accruing RENEWAL on the very first collected invoice
     * would pay the seller twice for that same premium. So the first collection records its own
     * invoiceId on the projection and accrues nothing, and only a LATER invoice earns RENEWAL.
     *
     * <p>The guard is keyed on {@code first_invoice_id} rather than a boolean deliberately.
     * Storing only "the first has happened" would make a REDELIVERED first-collection event read
     * as a second invoice and accrue exactly the RENEWAL the guard exists to prevent -- and unlike
     * every other idempotency path in this module, {@code sourceRef} dedup cannot save it, because
     * the first collection intentionally writes no accrual row for a redelivery to collide with.
     * Comparing the identity closes that hole: null means none yet, an equal id means a redelivery,
     * anything else is a genuine renewal.
     *
     * <p>RENEWAL credits the SELLER ONLY -- no OVERRIDE or SUPERVISOR_OVERRIDE. That is Task 4's
     * documented (and invented, and flagged) rule, enforced inside {@link
     * CommissionCalculator#calculate} itself, which is why the ancestors argument below is
     * deliberately {@code List.of()} rather than a hierarchy walk.
     */
    private void handlePremiumCollected(Map<String, Object> payload) {
        UUID tenantId = TenantContext.get();
        UUID invoiceId = (UUID) payload.get("invoiceId");
        String policyNumber = (String) payload.get("policyNumber");
        @SuppressWarnings("unchecked")
        Map<String, Object> amount = (Map<String, Object>) payload.get("amount");
        BigDecimal collectedAmount = new BigDecimal((String) amount.get("amount"));
        String currency = (String) amount.get("currencyCode");

        Optional<PolicyProjection> maybeProjection = policyProjectionRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber);
        if (maybeProjection.isEmpty()) {
            // A pre-M7 policy: issued before this module existed, so it has no projection row and
            // genuinely predates commission tracking. Log and move on, never throw -- the same
            // fail-silent-and-log contract handlePolicyLapsed documents.
            log.info("Premium collected on policy {} (invoice {}) but it has no distribution projection row "
                + "(pre-M7 policy) -- no renewal commission to accrue", policyNumber, invoiceId);
            return;
        }
        PolicyProjection projection = maybeProjection.get();

        if (projection.isFirstCollection(invoiceId)) {
            projection.markFirstInvoiceCollected(invoiceId);
            policyProjectionRepository.save(projection);
            log.info("Invoice {} is policy {}'s first collected premium -- FIRST_YEAR commission was already "
                + "accrued at issuance, so no RENEWAL accrues here", invoiceId, policyNumber);
            return;
        }

        UUID agentId = projection.getAgentId();
        if (agentId == null) {
            log.info("Policy {} was sold direct (no agent of record) -- no renewal commission to accrue", policyNumber);
            return;
        }

        CommissionCalculator.AgentWithPlan seller =
            distributionApiImpl.resolveAgentWithPlan(tenantId, agentId, projection.getProductId());
        if (seller == null) {
            log.info("Policy {}'s agent {} does not resolve to an agent in tenant {} -- no renewal commission accrued",
                policyNumber, agentId, tenantId);
            return;
        }

        // The COLLECTED amount, not the projection's issuance premium: "actual production" is the
        // money that actually came in for this period, and the two can legitimately differ once an
        // endorsement has re-rated the policy.
        List<CommissionCalculator.Accrual> accruals = CommissionCalculator.calculate(
            seller, List.of(), TierType.RENEWAL, collectedAmount, currency);

        String period = YearMonth.now().toString();
        for (CommissionCalculator.Accrual accrual : accruals) {
            // sourceRef = invoiceId, so ux_commission_accrual_once makes a redelivered collection
            // for an already-accrued invoice a no-op.
            distributionApiImpl.persistAccrual(tenantId, accrual.agentId(), policyNumber, accrual.tierType(),
                    accrual.amount(), accrual.currency(), period, invoiceId.toString(), null,
                    "system:billing.PremiumCollected");
        }
    }
}
