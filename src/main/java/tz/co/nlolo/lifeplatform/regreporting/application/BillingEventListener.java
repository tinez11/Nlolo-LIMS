package tz.co.nlolo.lifeplatform.regreporting.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.regreporting.domain.PolicyDimension;
import tz.co.nlolo.lifeplatform.regreporting.domain.PremiumMovement;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.PolicyDimensionRepository;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.PremiumMovementRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Maintains {@code regreporting.premium_movement} off {@code billing.PremiumCollected}
 * (regreporting/V2 section 6). Mechanics and bean-naming rationale: see {@link
 * PolicyEventListener}.
 *
 * <p>{@code PremiumCollected} carries a {@code policyNumber} but no {@code productId} (verified
 * against asyncapi-events.yaml), and this fact table is attributed by product, so the product must
 * be resolved through {@code policy_dimension} exactly as {@link PolicyEventListener}'s Lapsed/
 * Matured/Surrendered/Reinstated handlers resolve theirs. When that lookup misses, the movement is
 * attributed to {@link ProjectionSupport#UNKNOWN_PRODUCT} rather than dropped.
 */
@Component("regreportingBillingEventListener")
public class BillingEventListener {

    private static final Logger log = LoggerFactory.getLogger(BillingEventListener.class);

    private static final String EVENT_PROCESSING_FAILED_COUNTER = "lifeplatform_regreporting_event_processing_failed_total";

    private final PolicyDimensionRepository policyDimensionRepository;
    private final PremiumMovementRepository premiumMovementRepository;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public BillingEventListener(PolicyDimensionRepository policyDimensionRepository,
                                 PremiumMovementRepository premiumMovementRepository,
                                 MeterRegistry meterRegistry,
                                 PlatformTransactionManager transactionManager) {
        this.policyDimensionRepository = policyDimensionRepository;
        this.premiumMovementRepository = premiumMovementRepository;
        this.meterRegistry = meterRegistry;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        switch (envelope.eventType()) {
            case "billing.PremiumCollected" -> withTenant(envelope, this::handlePremiumCollected);
            default -> { /* not regreporting-relevant here */ }
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
            meterRegistry.counter(EVENT_PROCESSING_FAILED_COUNTER, "eventType", envelope.eventType()).increment();
            log.error("regreporting failed to process {} for tenant {}", envelope.eventType(), envelope.tenantId(), e);
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }

    private void handlePremiumCollected(Map<String, Object> payload) {
        UUID tenantId = TenantContext.get();
        String policyNumber = (String) payload.get("policyNumber");
        @SuppressWarnings("unchecked")
        Map<String, Object> amountMap = (Map<String, Object>) payload.get("amount");
        BigDecimal amount = new BigDecimal((String) amountMap.get("amount"));
        String currency = (String) amountMap.get("currencyCode");
        String period = ProjectionSupport.quarterOfInstant((String) payload.get("collectedAt"));

        UUID productId = policyDimensionRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber)
            .map(PolicyDimension::getProductId)
            .orElseGet(() -> {
                log.warn("billing.PremiumCollected for policy {} has no policy_dimension row -- "
                    + "attributing to the UNKNOWN product sentinel rather than dropping the movement",
                    policyNumber);
                return ProjectionSupport.UNKNOWN_PRODUCT;
            });

        PremiumMovement movement = premiumMovementRepository
            .findByTenantIdAndPeriodAndProductId(tenantId, period, productId)
            .orElseGet(() -> new PremiumMovement(tenantId, period, productId, currency));
        movement.applyCollected(amount);
        premiumMovementRepository.save(movement);
    }
}
