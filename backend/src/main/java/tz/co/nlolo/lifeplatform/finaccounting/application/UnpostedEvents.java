package tz.co.nlolo.lifeplatform.finaccounting.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.finaccounting.api.UnpostedEventView;
import tz.co.nlolo.lifeplatform.finaccounting.domain.PostingFacts;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The queue of events the posting rules could not post (IFRS 17 I3a, finaccounting V12). Never a silent drop: the
 * facts are kept as the rules read them, so a retry re-runs the current rules on exactly what the event said. One open
 * row per (event, source); a further failure updates it, a success or a dismissal resolves it, and the resolved row
 * stays as the record. JDBC: the open row's uniqueness is a partial index, which an upsert names directly.
 */
@Component
class UnpostedEvents {

    enum Reason { UNMAPPED, REFUSED, ERROR }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    UnpostedEvents(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    void record(UUID tenantId, PostingFacts facts, String period, Reason reason, String detail, String ruleVersion) {
        jdbc.update("INSERT INTO finaccounting.unposted_event (tenant_id, event_type, source_ref, policy_number, period,"
                + " facts, reason, detail, rule_version) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)"
                + " ON CONFLICT (tenant_id, event_type, source_ref) WHERE resolved_at IS NULL DO UPDATE SET"
                + " reason = EXCLUDED.reason, detail = EXCLUDED.detail, rule_version = EXCLUDED.rule_version,"
                + " facts = EXCLUDED.facts, attempts = finaccounting.unposted_event.attempts + 1, last_attempt_at = now()",
            tenantId, facts.eventType(), facts.sourceRef(), facts.policyNumber(), period, write(facts), reason.name(),
            truncate(detail, 1000), ruleVersion);
    }

    /** The open row for this event, if any, is now posted. */
    void resolvePosted(UUID tenantId, String eventType, String sourceRef, UUID journalEntryId, String by) {
        jdbc.update("UPDATE finaccounting.unposted_event SET resolution = 'POSTED', resolved_at = now(), resolved_by = ?,"
                + " journal_entry_id = ? WHERE tenant_id = ? AND event_type = ? AND source_ref = ? AND resolved_at IS NULL",
            by, journalEntryId, tenantId, eventType, sourceRef);
    }

    /**
     * One person asks to dismiss (2026-11-12, findings 74 and 116): the event stays open, and still blocks month-end,
     * until a second person confirms.
     *
     * @return false when the row is not open or a dismissal is already asked for
     */
    boolean askDismissal(UUID tenantId, UUID id, String reason, String by) {
        return jdbc.update("UPDATE finaccounting.unposted_event SET dismissal_requested_by = ?, dismissal_requested_at = now(),"
                + " dismissal_reason = ? WHERE tenant_id = ? AND unposted_event_id = ? AND resolved_at IS NULL"
                + " AND dismissal_requested_by IS NULL",
            by, truncate(reason, 500), tenantId, id) == 1;
    }

    /** The second person confirms: the asked reason becomes the resolution's. @return false when not asked or not open */
    boolean confirmDismissal(UUID tenantId, UUID id, String by, boolean alone) {
        return jdbc.update("UPDATE finaccounting.unposted_event SET resolution = 'DISMISSED', resolution_reason = dismissal_reason,"
                + " resolved_at = now(), resolved_by = ?, dismissed_alone = ? WHERE tenant_id = ? AND unposted_event_id = ?"
                + " AND resolved_at IS NULL AND dismissal_requested_by IS NOT NULL",
            by, alone, tenantId, id) == 1;
    }

    /** A refused dismissal: the request is cleared and the event waits to be posted. */
    boolean refuseDismissal(UUID tenantId, UUID id) {
        return jdbc.update("UPDATE finaccounting.unposted_event SET dismissal_requested_by = NULL, dismissal_requested_at = NULL,"
                + " dismissal_reason = NULL WHERE tenant_id = ? AND unposted_event_id = ? AND resolved_at IS NULL"
                + " AND dismissal_requested_by IS NOT NULL", tenantId, id) == 1;
    }

    /**
     * A retry the rules in force post nothing for: closed at once, as a dismissal -- no amount leaves the books, since
     * there is none to book, so no second person is asked.
     */
    boolean closeNothingToPost(UUID tenantId, UUID id, String reason, String by) {
        return jdbc.update("UPDATE finaccounting.unposted_event SET resolution = 'DISMISSED', resolution_reason = ?,"
                + " resolved_at = now(), resolved_by = ? WHERE tenant_id = ? AND unposted_event_id = ? AND resolved_at IS NULL",
            truncate(reason, 500), by, tenantId, id) == 1;
    }

    /** Who asked to dismiss this open event, or null. */
    String dismissalRequestedBy(UUID tenantId, UUID id) {
        return jdbc.query("SELECT dismissal_requested_by FROM finaccounting.unposted_event WHERE tenant_id = ?"
            + " AND unposted_event_id = ?", (rs, i) -> rs.getString(1), tenantId, id).stream().findFirst().orElse(null);
    }

    Optional<UnpostedEventView> find(UUID tenantId, UUID id) {
        return jdbc.query("SELECT * FROM finaccounting.unposted_event WHERE tenant_id = ? AND unposted_event_id = ?",
            this::view, tenantId, id).stream().findFirst();
    }

    PostingFacts facts(UUID tenantId, UUID id) {
        String raw = jdbc.queryForObject("SELECT facts::text FROM finaccounting.unposted_event"
            + " WHERE tenant_id = ? AND unposted_event_id = ?", String.class, tenantId, id);
        return read(raw);
    }

    /** Open ones first (oldest first, as a work list), then the most recently resolved. Bounded: a queue, not a ledger. */
    List<UnpostedEventView> list(UUID tenantId, boolean openOnly) {
        return jdbc.query("SELECT * FROM finaccounting.unposted_event WHERE tenant_id = ?"
                + (openOnly ? " AND resolved_at IS NULL" : "")
                + " ORDER BY (resolved_at IS NULL) DESC, CASE WHEN resolved_at IS NULL THEN created_at END ASC,"
                + " resolved_at DESC LIMIT 500",
            this::view, tenantId);
    }

    /** A policy's open UNMAPPED events: what waited for its classification. */
    List<UUID> openUnmappedFor(UUID tenantId, String policyNumber) {
        return jdbc.queryForList("SELECT unposted_event_id FROM finaccounting.unposted_event WHERE tenant_id = ?"
            + " AND policy_number = ? AND reason = 'UNMAPPED' AND resolved_at IS NULL ORDER BY created_at",
            UUID.class, tenantId, policyNumber);
    }

    /** How many are open in this period or before it: each must be posted or dismissed before the period locks. */
    int openUpTo(UUID tenantId, String period) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM finaccounting.unposted_event"
            + " WHERE tenant_id = ? AND resolved_at IS NULL AND period <= ?", Integer.class, tenantId, period);
        return n == null ? 0 : n;
    }

    private UnpostedEventView view(ResultSet rs, int i) throws SQLException {
        PostingFacts facts = read(rs.getString("facts"));
        return new UnpostedEventView(rs.getObject("unposted_event_id", UUID.class), rs.getString("event_type"),
            rs.getString("source_ref"), rs.getString("policy_number"), rs.getString("period"), facts.currency(),
            facts.amounts(), facts.attributes(), rs.getString("reason"), rs.getString("detail"),
            rs.getString("rule_version"), rs.getInt("attempts"), instant(rs, "created_at"),
            instant(rs, "last_attempt_at"), rs.getString("resolution"), rs.getString("resolution_reason"),
            rs.getString("resolved_by"), instant(rs, "resolved_at"), rs.getObject("journal_entry_id", UUID.class),
            rs.getString("dismissal_requested_by"), instant(rs, "dismissal_requested_at"), rs.getString("dismissal_reason"),
            rs.getBoolean("dismissed_alone"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private String write(PostingFacts facts) {
        try {
            return json.writeValueAsString(facts);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not keep the facts of " + facts.eventType() + " " + facts.sourceRef(), e);
        }
    }

    private PostingFacts read(String raw) {
        try {
            return json.readValue(raw, PostingFacts.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable facts on an unposted event", e);
        }
    }

    private static String truncate(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }
}
