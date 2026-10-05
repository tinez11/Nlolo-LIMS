package tz.co.nlolo.lifeplatform.policy.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

/**
 * Gives PolicyApi.lapsePolicy its first real caller (M3's ledger recorded it as fully tested
 * but never invoked outside a test). Per the event catalog's own framing -- "billing recommends,
 * policy alone performs the lapse transition" -- this listener does not re-derive eligibility; it
 * trusts billing's recommendation and calls lapsePolicy directly. Policy.lapse()'s own domain
 * guard (ACTIVE or SUSPENDED only) is the authoritative check either way, so a stale/duplicate
 * recommendation against an already-lapsed policy fails loudly with InvalidPolicyStateException
 * rather than silently double-lapsing -- caught and logged below, same as
 * UnderwritingDecisionEventListener's own no-dead-letter posture.
 *
 * <p>One exception (product step 3, decision Q6): an ACCOUNT-valued policy is never lapsed by
 * arrears. Its savings account keeps paying its own fee, and the policy lapses only when the account
 * cannot -- accumulation's exhaustion, through {@code lapseExhaustedAccount}.
 */
@Component
public class PolicyLapseRecommendedEventListener {

    private static final Logger log = LoggerFactory.getLogger(PolicyLapseRecommendedEventListener.class);

    private final PolicyApi policyApi;
    private final ProductApi productApi;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public PolicyLapseRecommendedEventListener(PolicyApi policyApi, ProductApi productApi,
                                               PlatformTransactionManager transactionManager) {
        this.policyApi = policyApi;
        this.productApi = productApi;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        if (!"billing.PolicyLapseRecommended".equals(envelope.eventType())) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) envelope.payload();
        String policyNumber = (String) payload.get("policyNumber");
        UUID previousTenant = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            requiresNewTransactionTemplate.executeWithoutResult(status -> {
                // Billing still raises the invoice and still reminds the customer; it is only the
                // lapse it recommends that does not apply to an account-valued policy.
                UUID versionId = policyApi.getPolicy(policyNumber).productVersionId();
                if (productApi.resolveAccumulationPlan(versionId).isAccount()) {
                    log.info("Not lapsing policy {} on billing's recommendation: it is valued by its account", policyNumber);
                    return;
                }
                // Unit-linked (product step 6, plan R8): a version that lapses on EXHAUSTION keeps its cover paid for
                // from units while there are any, once its minimum premium-paying years have passed -- unitlinked
                // lapses it when the fund can no longer meet a month's charges. Inside those years, or under
                // NON_PAYMENT, billing's recommendation applies as for any policy.
                tz.co.nlolo.lifeplatform.product.api.UnitLinkedPlan unitLinked = productApi.resolveUnitLinkedPlan(versionId);
                if (unitLinked.unitLinked()
                        && unitLinked.lapseRule() == tz.co.nlolo.lifeplatform.product.api.UnitLinkedPlan.LapseRule.EXHAUSTION) {
                    tz.co.nlolo.lifeplatform.policy.api.PolicyView policy = policyApi.getPolicy(policyNumber);
                    java.time.LocalDate start = policy.commencementDate() != null ? policy.commencementDate() : policy.issueDate();
                    boolean pastMinimum = unitLinked.minimumPremiumYears() == null || !java.time.LocalDate
                        .now(java.time.ZoneId.of("Africa/Dar_es_Salaam")).isBefore(start.plusYears(unitLinked.minimumPremiumYears()));
                    if (pastMinimum) {
                        log.info("Not lapsing unit-linked policy {} on billing's recommendation: it lapses only when its"
                            + " fund is exhausted", policyNumber);
                        return;
                    }
                }
                policyApi.lapsePolicy(policyNumber, "system:billing-lapse-recommendation");
            });
        } catch (Exception e) {
            log.error("Automatic lapse failed for policy {} following billing's recommendation", policyNumber, e);
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }
}
