package tz.co.nlolo.lifeplatform.regreporting.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.regreporting.domain.ReinsuranceMovement;
import tz.co.nlolo.lifeplatform.regreporting.infrastructure.ReinsuranceMovementRepository;
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
 * Maintains {@code regreporting.reinsurance_movement} off {@code reinsurance.CessionRecorded}
 * (regreporting/V2 section 6). Mechanics and bean-naming rationale: see {@link
 * PolicyEventListener}.
 *
 * <p>No dimension lookup here, and so no {@code UNKNOWN} sentinel: {@link ReinsuranceMovement} is
 * deliberately not attributed by product at all (see its own javadoc) -- a cession arrives from
 * {@code reinsurance}'s own transaction reacting to {@code PolicyIssued}, unordered against this
 * module's own {@code PolicyIssued} listener, so keying by product would race against the
 * dimension row a cession would need.
 *
 * <p>{@code CessionRecorded} carries no business timestamp at all (verified against
 * asyncapi-events.yaml) -- every cession is attributed to today's quarter, the period-derivation
 * rule's fallback.
 *
 * <p>{@code cededPremium} is legitimately null on the wire (the producer's own javadoc: "cededPremium
 * is legitimately null when a ceded premium rounds to zero") -- treated as zero here rather than
 * thrown on, per the Task 6 brief.
 */
@Component("regreportingReinsuranceEventListener")
public class ReinsuranceEventListener {

    private static final Logger log = LoggerFactory.getLogger(ReinsuranceEventListener.class);

    private static final String EVENT_PROCESSING_FAILED_COUNTER = "lifeplatform_regreporting_event_processing_failed_total";

    private final ReinsuranceMovementRepository reinsuranceMovementRepository;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public ReinsuranceEventListener(ReinsuranceMovementRepository reinsuranceMovementRepository,
                                     MeterRegistry meterRegistry,
                                     PlatformTransactionManager transactionManager) {
        this.reinsuranceMovementRepository = reinsuranceMovementRepository;
        this.meterRegistry = meterRegistry;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        switch (envelope.eventType()) {
            case "reinsurance.CessionRecorded" -> withTenant(envelope, this::handleCessionRecorded);
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

    private void handleCessionRecorded(Map<String, Object> payload) {
        UUID tenantId = TenantContext.get();
        @SuppressWarnings("unchecked")
        Map<String, Object> cededAmountMap = (Map<String, Object>) payload.get("cededAmount");
        BigDecimal cededRisk = new BigDecimal((String) cededAmountMap.get("amount"));
        String currency = (String) cededAmountMap.get("currencyCode");

        @SuppressWarnings("unchecked")
        Map<String, Object> cededPremiumMap = (Map<String, Object>) payload.get("cededPremium");
        BigDecimal cededPremium = cededPremiumMap == null
            ? BigDecimal.ZERO
            : new BigDecimal((String) cededPremiumMap.get("amount"));

        String period = ProjectionSupport.currentQuarter();
        ReinsuranceMovement movement = reinsuranceMovementRepository
            .findByTenantIdAndPeriod(tenantId, period)
            .orElseGet(() -> new ReinsuranceMovement(tenantId, period, currency));
        movement.applyCeded(cededRisk, cededPremium);
        reinsuranceMovementRepository.save(movement);
    }
}
