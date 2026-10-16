package tz.co.nlolo.lifeplatform.dashboard.api;

import java.time.Instant;
import java.util.Map;

/**
 * One event from the audit log, as a dashboard shows it: what happened and when -- never who. The audit log records
 * no actor in general ({@code AuditApi.listEvents}), and a feed that implied one would be presenting an event stream as
 * compliance evidence.
 *
 * @param refs the whitelisted references from the event's payload (case, claim, policy, receipt, disbursement), so the
 *     console can word the event and link it; only these keys ever leave the server
 * @param amount the event's money where it carries one; null otherwise
 */
public record ActivityItem(String eventType, Instant occurredAt, Map<String, String> refs, Money amount) {}
