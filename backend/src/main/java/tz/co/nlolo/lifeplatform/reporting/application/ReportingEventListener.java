package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;

import java.util.Map;

/**
 * Every domain event, after its producer commits. The bean name is explicit: billing, distribution,
 * reinsurance and regreporting each declare an unqualified PolicyEventListener, and a duplicate simple
 * name fails context startup for the whole suite.
 */
@Component("reportingDomainEventListener")
public class ReportingEventListener {

    private final EventApplier applier;

    public ReportingEventListener(EventApplier applier) {
        this.applier = applier;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        if (!(envelope.payload() instanceof Map<?, ?> raw)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) raw;
        applier.apply(envelope.tenantId(), envelope.eventId(), envelope.eventType(), envelope.occurredAt(), payload);
    }
}
