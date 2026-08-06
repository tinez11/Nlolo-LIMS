package tz.co.nlolo.lifeplatform.audit.api;

import java.util.List;

public interface AuditApi {
    List<AuditEntryView> getTrail(EntityRef entity, DateRange range);
}
