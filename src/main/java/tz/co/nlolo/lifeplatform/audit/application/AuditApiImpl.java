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
