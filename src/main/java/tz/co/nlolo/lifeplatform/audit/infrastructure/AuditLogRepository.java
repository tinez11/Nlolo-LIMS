package tz.co.nlolo.lifeplatform.audit.infrastructure;

import tz.co.nlolo.lifeplatform.audit.domain.AuditLogEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLogEntry, AuditLogEntry.AuditLogEntryId> {

    List<AuditLogEntry> findByTenantIdAndEventTypeAndOccurredAtBetween(
        UUID tenantId, String eventType, Instant from, Instant to);

    @Query(value = "SELECT * FROM audit.audit_log WHERE tenant_id = :tenantId " +
        "AND event_type LIKE :eventTypePrefix AND occurred_at BETWEEN :from AND :to " +
        "AND payload::text ILIKE :entityIdPattern ORDER BY occurred_at DESC", nativeQuery = true)
    List<AuditLogEntry> searchTrail(@Param("tenantId") UUID tenantId, @Param("eventTypePrefix") String eventTypePrefix,
                                     @Param("from") Instant from, @Param("to") Instant to,
                                     @Param("entityIdPattern") String entityIdPattern);
}
