package tz.co.nlolo.lifeplatform.audit.application;

import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.audit.api.AuditApi;
import tz.co.nlolo.lifeplatform.audit.api.AuditEntryView;
import tz.co.nlolo.lifeplatform.audit.api.DateRange;
import tz.co.nlolo.lifeplatform.audit.api.EntityRef;
import tz.co.nlolo.lifeplatform.audit.infrastructure.AuditLogRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * getTrail's entity search is a deliberately simple V1: audit_log has no
 * structured "which entity does this row concern" column (only tenant_id,
 * event_type, and a JSONB payload whose shape varies per event type) --
 * matching entityId as a substring of the JSONB payload's text form is
 * correct and testable without inventing a schema change docs/06 never
 * specified. Not indexed/optimized; fine for M1 since nothing calls this yet.
 */
@Service
public class AuditApiImpl implements AuditApi {

    private final AuditLogRepository repository;

    public AuditApiImpl(AuditLogRepository repository) {
        this.repository = repository;
    }

    /**
     * Absent filters become wide-open bounds rather than nulls.
     *
     * The query cannot take nulls: on Postgres a NULL bind has no inferable type,
     * so `(:p is null or col like concat(:p, '%'))` sends `bytea` and the server
     * rejects `character varying ~~ bytea`. An empty prefix is exactly equivalent
     * (`like '%'` matches everything), and EPOCH..MAX_INSTANT is exactly equivalent
     * to an unbounded range -- with the same cost, since either way an unbounded
     * query scans every monthly partition.
     */
    private static final java.time.Instant MAX_INSTANT = java.time.Instant.parse("9999-12-31T23:59:59Z");

    @Override
    public org.springframework.data.domain.Page<AuditEntryView> listEvents(
            String eventTypePrefix, DateRange range, org.springframework.data.domain.Pageable pageable) {
        String prefix = (eventTypePrefix == null || eventTypePrefix.isBlank()) ? "" : eventTypePrefix;
        java.time.Instant from = (range == null || range.from() == null) ? java.time.Instant.EPOCH : range.from();
        java.time.Instant to = (range == null || range.to() == null) ? MAX_INSTANT : range.to();
        return repository.listEvents(TenantContext.get(), prefix, from, to, pageable)
            .map(e -> new AuditEntryView(e.getEventId(), e.getEventType(), e.getOccurredAt(), e.getPayload()));
    }

    @Override
    public List<AuditEntryView> getTrail(EntityRef entity, DateRange range) {
        String eventTypePrefix = entity.entityType() + ".%";
        String entityIdPattern = "%" + entity.entityId() + "%";
        return repository.searchTrail(TenantContext.get(), eventTypePrefix, range.from(), range.to(), entityIdPattern)
            .stream()
            .map(e -> new AuditEntryView(e.getEventId(), e.getEventType(), e.getOccurredAt(), e.getPayload()))
            .toList();
    }
}
