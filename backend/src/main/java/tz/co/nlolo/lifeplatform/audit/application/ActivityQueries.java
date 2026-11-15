package tz.co.nlolo.lifeplatform.audit.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.audit.api.EventRefs;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The activity log's read side (2026-11-14): one list of people's actions and of what the platform did by itself (the
 * business events no action raised), filtered and paged in the database; one line opened; and the CSV rows.
 *
 * <p>Every filter is bound, an absent one as '' -- never a null bind, which Postgres cannot type (see
 * AuditLogRepository.listEvents).
 */
@Component
public class ActivityQueries {

    public record Filters(String person, String recordType, String recordId, String q, String kind, String outcome,
                          Instant from, Instant to) {}

    public record Money(String amount, String currencyCode) {
        static Money of(BigDecimal amount, String currencyCode) {
            return amount == null || currencyCode == null ? null
                : new Money(amount.setScale(2, RoundingMode.HALF_UP).toPlainString(), currencyCode);
        }
    }

    /**
     * One line. {@code actorKind} UNRECORDED: an event from before the activity log began, when who acted was not yet
     * kept -- not the platform acting by itself, which is SYSTEM (2026-11-15 critique: 6,083 dev events
     * from before the log read as "System did it"). {@code actedAlone}: the person approved their own work, which the
     * line says rather than a separate "Acted alone" line beneath it.
     */
    public record Line(UUID id, Instant occurredAt, String actorSubject, String actorName, String actorKind,
                       String label, String kind, String recordType, String recordId, String recordLabel, Money amount,
                       String outcome, String reason, int eventCount, boolean actedAlone) {}

    public record Page(List<Line> items, int page, int pageSize, long totalElements) {}

    public record Person(String subject, String name) {}

    public record Change(String field, String before, String after) {}

    public record Event(String eventType, Instant occurredAt, Map<String, String> refs, Money amount) {}

    public record Technical(String method, String route, Integer statusCode, String body) {}

    public record Detail(Line line, List<Change> changes, List<Event> events, Technical technical) {}

    /** Each module's events, by the kind a person filters on -- the same split as audit/activity-labels.tsv. */
    private static final String EVENT_KIND = """
        CASE split_part(a.event_type, '.', 1)
          WHEN 'claims' THEN 'claims' WHEN 'benefitpayout' THEN 'claims'
          WHEN 'billing' THEN 'money' WHEN 'payment' THEN 'money' WHEN 'policyloan' THEN 'money'
          WHEN 'finaccounting' THEN 'money' WHEN 'accumulation' THEN 'money' WHEN 'unitlinked' THEN 'money'
          WHEN 'annuity' THEN 'money' WHEN 'bonus' THEN 'money'
          WHEN 'policy' THEN 'policies' WHEN 'underwriting' THEN 'policies' WHEN 'omnichannel' THEN 'policies'
          WHEN 'party' THEN 'people' WHEN 'iam' THEN 'people' WHEN 'distribution' THEN 'people'
          WHEN 'access' THEN 'people'
          WHEN 'product' THEN 'products' WHEN 'refdata' THEN 'products' WHEN 'reinsurance' THEN 'products'
          ELSE 'settings' END""";

    private static final String ACTIONS = """
        SELECT v.activity_id AS id, v.occurred_at, v.actor_subject, v.actor_name, v.actor_kind, v.label, v.kind,
               v.record_type, v.record_id, v.record_label, v.amount, v.currency, v.outcome, v.reason,
               (SELECT count(*) FROM audit.audit_log e WHERE e.action_id = v.activity_id
                  AND e.occurred_at >= v.occurred_at - interval '1 minute') AS event_count,
               EXISTS (SELECT 1 FROM audit.audit_log e WHERE e.action_id = v.activity_id
                  AND e.event_type = 'access.ActedAlone'
                  AND e.occurred_at >= v.occurred_at - interval '1 minute') AS acted_alone
        FROM audit.activity v
        WHERE v.tenant_id = :tenant AND v.occurred_at BETWEEN :from AND :to
          AND (:person = '' OR v.actor_subject = :person)
          AND (:kind = '' OR v.kind = :kind)
          AND (:outcome = '' OR v.outcome = :outcome)
          AND (:recordType = '' OR (v.record_type = :recordType AND v.record_id = :recordId))
          AND (:q = '' OR v.record_label ILIKE :qLike OR v.record_id ILIKE :qLike OR v.label ILIKE :qLike)""";

    /**
     * The record an event is about, from its payload, most specific first -- so a System line names it and opens it
     * like an action does. Keys as the producers write them (audit.audit_log, 2026-11-15: 6,083 dev events, all but a
     * handful carrying one of these).
     */
    private static final String EVENT_RECORD_TYPE = """
        CASE WHEN a.payload->>'policyNumber' IS NOT NULL THEN 'policy'
             WHEN a.payload->>'claimId' IS NOT NULL THEN 'claim'
             WHEN a.payload->>'caseId' IS NOT NULL THEN 'underwriting case'
             WHEN a.payload->>'agentId' IS NOT NULL THEN 'agent'
             WHEN a.payload->>'partyId' IS NOT NULL THEN 'client' END""";
    private static final String EVENT_RECORD_ID = """
        coalesce(a.payload->>'policyNumber', a.payload->>'claimId', a.payload->>'caseId', a.payload->>'agentId',
                 a.payload->>'partyId')""";

    /**
     * Before the activity log began recording (it merged and reached dev on 2026-11-15), who acted was not kept, so an
     * event from then is UNRECORDED rather than SYSTEM. A fixed date, not "before the tenant's first action": a new
     * tenant's scheduled runs before anyone has acted are still the platform acting by itself.
     */
    private static final String EVENT_ACTOR_KIND = """
        CASE WHEN a.occurred_at < TIMESTAMPTZ '2026-11-15 00:00:00+03' THEN 'UNRECORDED' ELSE 'SYSTEM' END""";

    private static final String EVENTS = """
        SELECT a.event_id AS id, a.occurred_at, NULL AS actor_subject, NULL AS actor_name, %s AS actor_kind,
               a.event_type AS label, %s AS kind, %s AS record_type, %s AS record_id,
               a.payload->>'policyNumber' AS record_label, NULL::numeric AS amount, NULL AS currency,
               'DONE' AS outcome, NULL AS reason, 0 AS event_count, false AS acted_alone
        FROM audit.audit_log a
        WHERE a.tenant_id = :tenant AND a.action_id IS NULL AND a.occurred_at BETWEEN :from AND :to
          AND a.event_type NOT IN (:noise)
          AND (:person = '' OR :person = 'SYSTEM')
          AND (:outcome = '' OR :outcome = 'DONE')
          AND (:kind = '' OR %s = :kind)
          AND (:recordType = ''
               OR (:recordType = 'policy' AND a.payload->>'policyNumber' = :recordId)
               OR (:recordType = 'claim' AND a.payload->>'claimId' = :recordId)
               OR (:recordType = 'client' AND a.payload->>'partyId' = :recordId)
               OR (:recordType = 'agent' AND a.payload->>'agentId' = :recordId))
          AND (:q = '' OR a.payload::text ILIKE :qLike)""".formatted(
        EVENT_ACTOR_KIND, EVENT_KIND, EVENT_RECORD_TYPE, EVENT_RECORD_ID, EVENT_KIND);

    private static final String LINES = "(" + ACTIONS + ") UNION ALL (" + EVENTS + ")";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    public ActivityQueries(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public Page list(Filters f, int page, int pageSize) {
        MapSqlParameterSource p = params(f).addValue("limit", pageSize).addValue("offset", (long) page * pageSize);
        List<Line> items = jdbc.query("SELECT * FROM (" + LINES + ") lines ORDER BY occurred_at DESC, id"
            + " LIMIT :limit OFFSET :offset", p, ActivityQueries::line);
        Long total = jdbc.queryForObject("SELECT count(*) FROM (" + LINES + ") lines", p, Long.class);
        return new Page(items, page, pageSize, total == null ? 0 : total);
    }

    /** For the CSV: the same lines, newest first, at most {@code limit}. */
    @Transactional(readOnly = true)
    public List<Line> all(Filters f, int limit) {
        return jdbc.query("SELECT * FROM (" + LINES + ") lines ORDER BY occurred_at DESC, id LIMIT :limit",
            params(f).addValue("limit", limit), ActivityQueries::line);
    }

    /** Everyone who has acted in this tenant, by the name they last acted under. */
    @Transactional(readOnly = true)
    public List<Person> people() {
        return jdbc.query("""
            SELECT subject, name FROM (
              SELECT DISTINCT ON (actor_subject) actor_subject AS subject, actor_name AS name
              FROM audit.activity WHERE tenant_id = :tenant AND actor_subject IS NOT NULL
              ORDER BY actor_subject, occurred_at DESC) people
            ORDER BY lower(coalesce(name, subject))""",
            new MapSqlParameterSource("tenant", TenantContext.get()),
            (rs, i) -> new Person(rs.getString("subject"), rs.getString("name")));
    }

    /** One line opened: an action, or a System event when the id is an event's. */
    @Transactional(readOnly = true)
    public Optional<Detail> detail(UUID id) {
        MapSqlParameterSource p = new MapSqlParameterSource("tenant", TenantContext.get()).addValue("id", id);
        List<Map<String, Object>> action = jdbc.queryForList("""
            SELECT v.*, v.body::text AS body_text FROM audit.activity v
            WHERE v.tenant_id = :tenant AND v.activity_id = :id""", p);
        if (!action.isEmpty()) return Optional.of(actionDetail(action.get(0)));
        List<Map<String, Object>> event = jdbc.queryForList("""
            SELECT a.event_id, a.event_type, a.occurred_at, a.payload::text AS payload_text,
                   %s AS actor_kind, %s AS kind, %s AS record_type, %s AS record_id
            FROM audit.audit_log a
            WHERE a.tenant_id = :tenant AND a.event_id = :id AND a.action_id IS NULL LIMIT 1"""
            .formatted(EVENT_ACTOR_KIND, EVENT_KIND, EVENT_RECORD_TYPE, EVENT_RECORD_ID), p);
        if (event.isEmpty()) return Optional.empty();
        Map<String, Object> e = event.get(0);
        JsonNode payload = read((String) e.get("payload_text"));
        Instant at = ((Timestamp) e.get("occurred_at")).toInstant();
        String type = (String) e.get("event_type");
        // kind as the list gives it: an opened System line used to answer null, which the contract does not allow.
        Line line = new Line(id, at, null, null, (String) e.get("actor_kind"), type, (String) e.get("kind"),
            (String) e.get("record_type"),
            (String) e.get("record_id"),
            payload != null && payload.hasNonNull("policyNumber") ? payload.get("policyNumber").asText() : null,
            null, "DONE", null, 0, false);
        Event only = new Event(type, at, maskedRefs(EventRefs.refs(payload)), money(EventRefs.amount(payload)));
        return Optional.of(new Detail(masked(line), List.of(), List.of(only),
            new Technical(null, null, null, maskedJson(payload, (String) e.get("payload_text")))));
    }

    private Detail actionDetail(Map<String, Object> row) {
        UUID id = (UUID) row.get("activity_id");
        Instant at = ((Timestamp) row.get("occurred_at")).toInstant();
        String recordType = (String) row.get("record_type");
        String recordId = (String) row.get("record_id");
        String bodyText = (String) row.get("body_text");
        MapSqlParameterSource p = new MapSqlParameterSource("tenant", TenantContext.get()).addValue("id", id)
            .addValue("at", Timestamp.from(at));
        List<Event> events = jdbc.query("""
            SELECT event_type, occurred_at, payload::text AS payload_text FROM audit.audit_log
            WHERE tenant_id = :tenant AND action_id = :id AND occurred_at >= CAST(:at AS timestamptz) - interval '1 minute'
            ORDER BY occurred_at, recorded_at""", p, (rs, i) -> {
                JsonNode payload = read(rs.getString("payload_text"));
                return new Event(rs.getString("event_type"), rs.getTimestamp("occurred_at").toInstant(),
                    maskedRefs(EventRefs.refs(payload)), money(EventRefs.amount(payload)));
            });
        boolean actedAlone = jdbc.query("""
            SELECT event_type FROM audit.audit_log
            WHERE tenant_id = :tenant AND action_id = :id AND occurred_at >= CAST(:at AS timestamptz) - interval '1 minute'
              AND event_type = 'access.ActedAlone' LIMIT 1""", p, (rs, i) -> true).size() > 0;
        Line line = new Line(id, at, (String) row.get("actor_subject"), (String) row.get("actor_name"),
            (String) row.get("actor_kind"), (String) row.get("label"), (String) row.get("kind"), recordType, recordId,
            (String) row.get("record_label"), Money.of((BigDecimal) row.get("amount"), (String) row.get("currency")),
            (String) row.get("outcome"), (String) row.get("reason"), events.size(), actedAlone);
        JsonNode body = read(bodyText);
        return new Detail(masked(line), maskedChanges(changes(body, recordType, recordId, at)), events,
            new Technical((String) row.get("http_method"), (String) row.get("route"), (Integer) row.get("status_code"),
                maskedJson(body, bodyText)));
    }

    /*
     * Masking (2026-11-15, the user, reversing 2026-11-14's "full when opened"): a phone or ID number is shown by its
     * last three everywhere in the log, the opened line included -- its changes, the events under it, and the body.
     */

    private static List<Change> maskedChanges(List<Change> changes) {
        return changes.stream()
            .map(c -> ActivityRedaction.personal(c.field())
                ? new Change(c.field(), c.before() == null ? null : ActivityRedaction.mask(c.before()),
                    c.after() == null ? null : ActivityRedaction.mask(c.after()))
                : c)
            .toList();
    }

    private static Map<String, String> maskedRefs(Map<String, String> refs) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        refs.forEach((k, v) -> out.put(k, ActivityRedaction.personal(k) && v != null ? ActivityRedaction.mask(v) : v));
        return out;
    }

    /** The body with personal numbers masked; a body that is not JSON (an upload note is) kept as it came. */
    private static String maskedJson(JsonNode parsed, String raw) {
        return parsed == null ? raw : ActivityRedaction.masked(parsed).toString();
    }

    /**
     * Each field sent, with the value it had before where an earlier completed change to the same record carried it.
     * Only the top level: a nested value is shown whole, cut to 200 characters.
     */
    private List<Change> changes(JsonNode body, String recordType, String recordId, Instant at) {
        List<Change> changes = new ArrayList<>();
        if (body == null || !body.isObject()) return changes;
        List<JsonNode> earlier = recordType == null ? List.of() : jdbc.query("""
            SELECT body::text AS b FROM audit.activity
            WHERE tenant_id = :tenant AND record_type = :type AND record_id = :id AND occurred_at < :at
              AND outcome = 'DONE' AND body IS NOT NULL
            ORDER BY occurred_at DESC LIMIT 20""",
            new MapSqlParameterSource("tenant", TenantContext.get()).addValue("type", recordType)
                .addValue("id", recordId).addValue("at", Timestamp.from(at)),
            (rs, i) -> read(rs.getString("b")));
        for (Iterator<Map.Entry<String, JsonNode>> it = body.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> field = it.next();
            String before = null;
            for (JsonNode old : earlier) {
                if (old != null && old.has(field.getKey())) {
                    before = text(old.get(field.getKey()));
                    break;
                }
            }
            String after = text(field.getValue());
            if (before != null && before.equals(after)) continue;   // sent again unchanged: not a change
            changes.add(new Change(field.getKey(), before, after));
        }
        return changes;
    }

    private static String text(JsonNode v) {
        if (v == null || v.isNull()) return null;
        String s = v.isValueNode() ? v.asText() : v.toString();
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    private JsonNode read(String text) {
        if (text == null) return null;
        try {
            return json.readTree(text);
        } catch (Exception unreadable) {
            return null;
        }
    }

    private static Money money(EventRefs.Amount amount) {
        return amount == null ? null : Money.of(amount.amount(), amount.currencyCode());
    }

    private static MapSqlParameterSource params(Filters f) {
        String q = f.q() == null ? "" : f.q().strip();
        return new MapSqlParameterSource("tenant", TenantContext.get())
            .addValue("from", Timestamp.from(f.from()))
            .addValue("to", Timestamp.from(f.to()))
            .addValue("person", blank(f.person()))
            .addValue("kind", blank(f.kind()))
            .addValue("outcome", blank(f.outcome()))
            .addValue("recordType", blank(f.recordType()))
            .addValue("recordId", blank(f.recordId()))
            .addValue("q", q)
            .addValue("qLike", "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
            .addValue("noise", List.copyOf(EventRefs.NOISE));
    }

    private static String blank(String value) {
        return value == null ? "" : value.strip();
    }

    private static Line line(ResultSet rs, int row) throws SQLException {
        return new Line(rs.getObject("id", UUID.class), rs.getTimestamp("occurred_at").toInstant(),
            rs.getString("actor_subject"), rs.getString("actor_name"), rs.getString("actor_kind"), rs.getString("label"),
            rs.getString("kind"), rs.getString("record_type"), rs.getString("record_id"), rs.getString("record_label"),
            Money.of(rs.getBigDecimal("amount"), rs.getString("currency")), rs.getString("outcome"),
            rs.getString("reason"), rs.getInt("event_count"), rs.getBoolean("acted_alone"));
    }

    /** Masked copies, for the list, the CSV and an opened line: a phone number or an ID number never shown whole. */
    public static Line masked(Line l) {
        return new Line(l.id(), l.occurredAt(), l.actorSubject(), l.actorName(), l.actorKind(), l.label(), l.kind(),
            l.recordType(), l.recordId(), l.recordLabel(), l.amount(), l.outcome(), maskDigits(l.reason()), l.eventCount(),
            l.actedAlone());
    }

    /** A run of nine or more digits in free text -- a phone or an ID number -- shown by its last three. */
    static String maskDigits(String text) {
        return text == null ? null : text.replaceAll("\\+?\\d{6,}(\\d{3})", "••••$1");
    }
}
