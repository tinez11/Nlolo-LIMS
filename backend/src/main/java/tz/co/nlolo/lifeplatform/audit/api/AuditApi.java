package tz.co.nlolo.lifeplatform.audit.api;

import java.util.List;

public interface AuditApi {
    List<AuditEntryView> getTrail(EntityRef entity, DateRange range);

    /**
     * The tenant-wide event feed, newest first.
     *
     * **This is a domain-event journal, not a who-did-what audit trail, and the
     * distinction is load-bearing.** `audit.audit_log` records
     * `(event_id, event_type, schema_version, sequence_number, occurred_at,
     * recorded_at, payload)` and is written by `DomainEventAuditListener`. There is
     * NO actor column, and no before/after or reason. A compliance screen wants
     * actor, action, record, before, after and reason -- five of those six do not
     * exist anywhere on this platform.
     *
     * So this answers "what happened in this tenant, in order". It does not answer
     * "who did it and why", and anything built on it must say so rather than
     * presenting an event feed as compliance evidence. Closing that gap means
     * either putting actor identity into every domain event payload or building a
     * second mechanism beside this one -- recorded as an open item in the M13
     * design spec.
     *
     * `eventTypePrefix` filters by module (`policy.`); both date bounds are
     * optional.
     */
    org.springframework.data.domain.Page<AuditEntryView> listEvents(
        String eventTypePrefix, DateRange range, org.springframework.data.domain.Pageable pageable);
}
