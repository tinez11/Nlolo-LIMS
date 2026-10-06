package tz.co.nlolo.lifeplatform.finaccounting.application;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.finaccounting.domain.PostingFacts;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every event the ledger posts, in one place (IFRS 17 I3a): the payload becomes facts ({@link PostingFactsExtractor}),
 * the facts become a journal through the posting rules ({@link PostingEngine}). Replaces the nine per-module
 * listeners and their hard-coded account pairs, which posted the same way but each decided its own accounts.
 *
 * <p>AFTER_COMMIT, as before: the emitting module's write is durable before the ledger reacts. The engine posts each
 * journal in a transaction of its own (REQUIRES_NEW -- a plain transaction joined from an AFTER_COMMIT callback never
 * commits) and queues what it cannot post, so nothing here is lost to a swallowed exception except an event whose
 * payload cannot even be read, which is logged and counted. The tenant context is saved, set and restored, since this
 * runs on the emitting module's own thread.
 *
 * <p>The event date is today in Dar es Salaam (the civil calendar, never UTC): it chooses the rules and the
 * classification in force and the period the journal posts to.
 *
 * <p>Bean name explicit: several modules declare listeners with generic names.
 */
@Component("finaccountingLedgerEventListener")
public class LedgerEventListener {

    private static final Logger log = LoggerFactory.getLogger(LedgerEventListener.class);
    static final ZoneId CIVIL = ZoneId.of("Africa/Dar_es_Salaam");
    private static final String FAILED = "lifeplatform_finaccounting_event_processing_failed_total";

    private final PostingEngine engine;
    private final MeterRegistry meterRegistry;

    public LedgerEventListener(PostingEngine engine, MeterRegistry meterRegistry) {
        this.engine = engine;
        this.meterRegistry = meterRegistry;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        String type = envelope.eventType();
        if (!PostingFactsExtractor.handles(type)) {
            return;
        }
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) envelope.payload();
            List<PostingFacts> all = PostingFactsExtractor.extract(type, payload, LocalDate.now(CIVIL));
            for (PostingFacts facts : all) {
                engine.post(envelope.tenantId(), facts, "system:" + type);
            }
        } catch (Exception e) {
            meterRegistry.counter(FAILED, "eventType", type).increment();
            log.error("finaccounting could not read {} for tenant {}", type, envelope.tenantId(), e);
        } finally {
            if (previous != null) {
                TenantContext.set(previous);
            } else {
                TenantContext.clear();
            }
        }
    }
}
