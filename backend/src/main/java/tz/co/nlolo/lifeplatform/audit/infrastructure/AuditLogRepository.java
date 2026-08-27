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

    /**
     * M13: the tenant-wide event feed, paged.
     *
     * Distinct from {@link #searchTrail}, which answers "what happened to THIS
     * entity" by matching an id inside the JSONB payload. This answers "what
     * happened in this tenant, in order".
     *
     * `eventTypePrefix` filters by module (`policy.`), since event_type is
     * `module.EventName`. An unbounded query scans every monthly partition of what
     * the schema comments call the single highest-volume table on the platform --
     * the caller is expected to bound it, and the page-size cap keeps an unbounded
     * call from being catastrophic rather than making it free.
     *
     * EVERY PARAMETER IS MANDATORY HERE, and the caller substitutes wide-open
     * defaults for absent filters (see {@code AuditApiImpl.listEvents}). The
     * obvious shape -- {@code (:p is null or col like concat(:p, '%'))} -- FAILS on
     * Postgres: a NULL bind has no inferable type, so the driver sends it as
     * `bytea` and the server rejects `character varying ~~ bytea`, with a second
     * error that it "could not determine data type of parameter $4". Casting each
     * parameter would also work; keeping the query free of nulls is simpler and
     * leaves the SQL readable.
     */
    @Query("""
        select e from AuditLogEntry e
        where e.tenantId = :tenantId
          and e.eventType like concat(:eventTypePrefix, '%')
          and e.occurredAt >= :from
          and e.occurredAt <= :to
        """)
    org.springframework.data.domain.Page<AuditLogEntry> listEvents(
        @Param("tenantId") UUID tenantId,
        @Param("eventTypePrefix") String eventTypePrefix,
        @Param("from") Instant from,
        @Param("to") Instant to,
        org.springframework.data.domain.Pageable pageable);

    @Query(value = "SELECT * FROM audit.audit_log WHERE tenant_id = :tenantId " +
        "AND event_type LIKE :eventTypePrefix AND occurred_at BETWEEN :from AND :to " +
        "AND payload::text ILIKE :entityIdPattern ORDER BY occurred_at DESC", nativeQuery = true)
    List<AuditLogEntry> searchTrail(@Param("tenantId") UUID tenantId, @Param("eventTypePrefix") String eventTypePrefix,
                                     @Param("from") Instant from, @Param("to") Instant to,
                                     @Param("entityIdPattern") String entityIdPattern);
}
