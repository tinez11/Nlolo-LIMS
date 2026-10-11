package tz.co.nlolo.lifeplatform.reporting.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.audit.api.AuditApi;
import tz.co.nlolo.lifeplatform.audit.api.AuditEntryView;
import tz.co.nlolo.lifeplatform.audit.api.DateRange;
import tz.co.nlolo.lifeplatform.reporting.api.RebuildStatusView;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ProjectionStore;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Re-derives one tenant's reporting rows from the audit journal (AuditApi, the domain-event journal every
 * event is written to). Clears the tenant, then replays party, policy and billing events oldest first through
 * the same EventApplier the live listener uses.
 *
 * <p>Three passes, one per module prefix, because AuditApi filters by one prefix. Order matters only within
 * a module's own events (a lapse after its activation), and each pass keeps that order. Live events keep
 * arriving during a rebuild; applied_event makes any overlap harmless.
 *
 * <p>What it cannot recover: an event the audit listener itself failed to store (audit.failed_event). The
 * journal starts at the oldest audit partition, August 2026.
 */
@Service
public class RebuildService {

    static final List<String> PREFIXES = List.of("party.", "policy.", "billing.");
    private static final int PAGE_SIZE = 500;
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final Logger log = LoggerFactory.getLogger(RebuildService.class);

    private final AuditApi auditApi;
    private final ProjectionStore store;
    private final EventApplier applier;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final Map<UUID, RebuildStatusView> statuses = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "reporting-rebuild");
        t.setDaemon(true);
        return t;
    });

    public RebuildService(AuditApi auditApi, ProjectionStore store, EventApplier applier, ObjectMapper mapper,
                          PlatformTransactionManager transactionManager) {
        this.auditApi = auditApi;
        this.store = store;
        this.applier = applier;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Starts a rebuild unless one is already running for this tenant, in which case that one is returned. */
    public RebuildStatusView start(UUID tenantId, String requestedBy) {
        RebuildStatusView running = new RebuildStatusView("RUNNING", 0, 0, Instant.now(), null);
        RebuildStatusView current = statuses.compute(tenantId,
            (key, prior) -> prior != null && "RUNNING".equals(prior.state()) ? prior : running);
        if (current != running) {
            return current;
        }
        log.info("reporting rebuild for tenant {} requested by {}", tenantId, requestedBy);
        executor.submit(() -> statuses.put(tenantId, rebuild(tenantId, running.startedAt())));
        return running;
    }

    public RebuildStatusView status(UUID tenantId) {
        return statuses.getOrDefault(tenantId, RebuildStatusView.idle());
    }

    /** Synchronous; events after {@code startedAt} are left to the live listener. */
    RebuildStatusView rebuild(UUID tenantId, Instant startedAt) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenantId);
        long replayed = 0;
        long failed = 0;
        try {
            tx.executeWithoutResult(status -> store.deleteTenant(tenantId));
            DateRange upTo = new DateRange(null, startedAt);
            for (String prefix : PREFIXES) {
                int page = 0;
                Page<AuditEntryView> entries;
                do {
                    entries = auditApi.listEvents(prefix, upTo,
                        PageRequest.of(page++, PAGE_SIZE, Sort.by("occurredAt", "auditLogId")));
                    for (AuditEntryView e : entries.getContent()) {
                        Map<String, Object> payload = mapper.readValue(e.payloadJson(), MAP);
                        switch (applier.apply(tenantId, e.eventId(), e.eventType(), e.occurredAt(), payload)) {
                            case APPLIED, DUPLICATE -> replayed++;
                            case FAILED -> failed++;
                            case IGNORED -> { }
                        }
                    }
                } while (entries.hasNext());
            }
            return new RebuildStatusView(failed == 0 ? "DONE" : "DONE_WITH_FAILURES", replayed, failed, startedAt, Instant.now());
        } catch (Exception e) {
            log.error("reporting rebuild for tenant {} stopped", tenantId, e);
            return new RebuildStatusView("FAILED", replayed, failed, startedAt, Instant.now());
        } finally {
            if (previous != null) TenantContext.set(previous); else TenantContext.clear();
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
