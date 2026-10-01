package tz.co.nlolo.lifeplatform.benefitpayout.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The policy lifecycle, as it affects what is owed. Task 5 adds lapse, reinstatement, paid-up and
 * surrender here; issuance is what makes the schedule exist.
 *
 * <p>Envelope-only, so {@code billing}, {@code claims} and {@code payment} never become
 * compile-time dependencies of this module.
 */
// An explicit bean name because five other modules declare a PolicyEventListener, and two beans
// sharing one default name fail application startup outright.
@Component("benefitpayoutPolicyEventListener")
public class PolicyEventListener {

    private static final Logger log = LoggerFactory.getLogger(PolicyEventListener.class);

    private final BenefitPayoutApiImpl api;
    private final TransactionTemplate requiresNew;

    public PolicyEventListener(BenefitPayoutApiImpl api, PlatformTransactionManager transactionManager) {
        this.api = api;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        if ("policy.PolicyIssued".equals(envelope.eventType())) {
            withTenant(envelope, p -> api.expandForIssuedPolicy(
                (String) p.get("policyNumber"),
                (UUID) p.get("productVersionId"),
                LocalDate.parse((String) p.get("issueDate")),
                p.get("premiumPayingUntil") != null ? LocalDate.parse((String) p.get("premiumPayingUntil")) : null,
                (String) p.get("premiumFrequency")));
        }
    }

    /**
     * Run one handler under the envelope's own tenant, in its own transaction, and never let a
     * failure escape -- a payout schedule that cannot be written must not roll back the issuance
     * of the policy itself.
     */
    void withTenant(DomainEventEnvelope<?> envelope, Consumer<Map<String, Object>> handler) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) envelope.payload();
            requiresNew.executeWithoutResult(status -> handler.accept(payload));
        } catch (Exception e) {
            log.error("benefitpayout failed to process {} for tenant {}", envelope.eventType(), envelope.tenantId(), e);
        } finally {
            if (previous != null) {
                TenantContext.set(previous);
            } else {
                TenantContext.clear();
            }
        }
    }
}
