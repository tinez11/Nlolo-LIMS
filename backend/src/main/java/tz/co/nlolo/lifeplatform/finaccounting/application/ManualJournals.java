package tz.co.nlolo.lifeplatform.finaccounting.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.finaccounting.api.ManualJournalInput;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;

import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The store of manual journal drafts (IFRS 17 I4, finaccounting V14) -- the header and its lines. JDBC, like the
 * other I3/I4 tables: every state change is one guarded UPDATE ("... AND status = ?"), so two people acting on one
 * draft at once cannot both win.
 */
@Component
class ManualJournals {

    record Header(UUID id, String status, String period, String currency, String title, String reason,
                  String reasonCode, String templateId, UUID reversesJournalId, LocalDate autoReverseOn,
                  List<String> documentRefs, String preparer, Instant preparedAt, Instant submittedAt,
                  String decidedBy, Instant decidedAt, String decisionReason, UUID journalEntryId) {}

    record Draft(Header header, List<ManualJournalInput.Line> lines) {}

    private final JdbcTemplate jdbc;

    ManualJournals(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    UUID insert(UUID tenantId, String period, ManualJournalInput in, UUID reversesJournalId, String preparer) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO finaccounting.manual_journal (manual_journal_id, tenant_id, status, period, currency,"
                + " title, reason, reason_code, template_id, reverses_journal_id, auto_reverse_on, preparer)"
                + " VALUES (?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, tenantId, period, currencyOf(in), trim(in.title(), 200), trim(in.reason(), 500), blankToNull(in.reasonCode()),
            in.templateId(), reversesJournalId, in.autoReverseOn() == null ? null : Date.valueOf(in.autoReverseOn()),
            preparer);
        writeLines(tenantId, id, in.lines());
        return id;
    }

    void updateDraft(UUID tenantId, UUID id, String period, ManualJournalInput in) {
        jdbc.update("UPDATE finaccounting.manual_journal SET period = ?, currency = ?, title = ?, reason = ?,"
                + " reason_code = ?, template_id = ?, auto_reverse_on = ?, version = version + 1"
                + " WHERE tenant_id = ? AND manual_journal_id = ? AND status = 'DRAFT'",
            period, currencyOf(in), trim(in.title(), 200), trim(in.reason(), 500), blankToNull(in.reasonCode()),
            in.templateId(), in.autoReverseOn() == null ? null : Date.valueOf(in.autoReverseOn()), tenantId, id);
        replaceLines(tenantId, id, in.lines());
    }

    void replaceLines(UUID tenantId, UUID id, List<ManualJournalInput.Line> lines) {
        jdbc.update("DELETE FROM finaccounting.manual_journal_line WHERE tenant_id = ? AND manual_journal_id = ?",
            tenantId, id);
        writeLines(tenantId, id, lines);
    }

    private void writeLines(UUID tenantId, UUID id, List<ManualJournalInput.Line> lines) {
        int no = 0;
        for (ManualJournalInput.Line l : lines) {
            no++;
            jdbc.update("INSERT INTO finaccounting.manual_journal_line (manual_journal_id, line_no, tenant_id, account_code,"
                    + " direction, amount, description, branch, fund, reference_type, reference)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, no, tenantId, l.accountCode(), l.side() == null ? null : l.side().name(), l.amount(),
                trim(l.description(), 300), blankToNull(l.branch()), blankToNull(l.fund()), blankToNull(l.referenceType()),
                trim(l.reference(), 100));
        }
    }

    Optional<Draft> find(UUID tenantId, UUID id) {
        List<Header> headers = jdbc.query("SELECT * FROM finaccounting.manual_journal WHERE tenant_id = ?"
            + " AND manual_journal_id = ?", this::header, tenantId, id);
        return headers.stream().findFirst().map(h -> new Draft(h, lines(tenantId, id)));
    }

    List<Draft> list(UUID tenantId, String status, String period) {
        List<Header> headers = jdbc.query("SELECT * FROM finaccounting.manual_journal WHERE tenant_id = ?"
                + " AND (?::text IS NULL OR status = ?) AND (?::text IS NULL OR period = ?)"
                + " ORDER BY prepared_at DESC LIMIT 500",
            this::header, tenantId, status, status, period, period);
        return headers.stream().map(h -> new Draft(h, lines(tenantId, h.id()))).toList();
    }

    /** The draft reversing {@code journalEntryId}, if one is live (not rejected). */
    Optional<UUID> liveReversalOf(UUID tenantId, UUID journalEntryId) {
        return jdbc.queryForList("SELECT manual_journal_id FROM finaccounting.manual_journal WHERE tenant_id = ?"
                + " AND reverses_journal_id = ? AND status <> 'REJECTED'", UUID.class, tenantId, journalEntryId)
            .stream().findFirst();
    }

    /** @return true when the draft was in {@code from} and is now in {@code to} */
    boolean move(UUID tenantId, UUID id, String from, String to) {
        String stamp = switch (to) {
            case "SUBMITTED" -> ", submitted_at = now()";
            case "DRAFT" -> ", submitted_at = NULL";
            default -> "";
        };
        return jdbc.update("UPDATE finaccounting.manual_journal SET status = ?" + stamp + ", version = version + 1"
            + " WHERE tenant_id = ? AND manual_journal_id = ? AND status = ?", to, tenantId, id, from) == 1;
    }

    boolean approve(UUID tenantId, UUID id, String approver, UUID journalEntryId) {
        return jdbc.update("UPDATE finaccounting.manual_journal SET status = 'APPROVED', decided_by = ?, decided_at = now(),"
                + " journal_entry_id = ?, version = version + 1"
                + " WHERE tenant_id = ? AND manual_journal_id = ? AND status = 'SUBMITTED'",
            approver, journalEntryId, tenantId, id) == 1;
    }

    boolean reject(UUID tenantId, UUID id, String by, String reason) {
        return jdbc.update("UPDATE finaccounting.manual_journal SET status = 'REJECTED', decided_by = ?, decided_at = now(),"
                + " decision_reason = ?, version = version + 1"
                + " WHERE tenant_id = ? AND manual_journal_id = ? AND status = 'SUBMITTED'",
            by, trim(reason, 500), tenantId, id) == 1;
    }

    void addDocument(UUID tenantId, UUID id, String ref) {
        jdbc.update("UPDATE finaccounting.manual_journal SET document_refs = array_append(document_refs, ?)"
            + " WHERE tenant_id = ? AND manual_journal_id = ? AND status = 'DRAFT' AND NOT (? = ANY(document_refs))",
            ref, tenantId, id, ref);
    }

    void removeDocument(UUID tenantId, UUID id, String ref) {
        jdbc.update("UPDATE finaccounting.manual_journal SET document_refs = array_remove(document_refs, ?)"
            + " WHERE tenant_id = ? AND manual_journal_id = ? AND status = 'DRAFT'", ref, tenantId, id);
    }

    private List<ManualJournalInput.Line> lines(UUID tenantId, UUID id) {
        return jdbc.query("SELECT * FROM finaccounting.manual_journal_line WHERE tenant_id = ? AND manual_journal_id = ?"
                + " ORDER BY line_no",
            (rs, i) -> new ManualJournalInput.Line(rs.getString("account_code"),
                rs.getString("direction") == null ? null : PostingDirection.valueOf(rs.getString("direction")),
                rs.getBigDecimal("amount"), rs.getString("description"), rs.getString("branch"), rs.getString("fund"),
                rs.getString("reference_type"), rs.getString("reference")),
            tenantId, id);
    }

    private Header header(ResultSet rs, int i) throws SQLException {
        Array refs = rs.getArray("document_refs");
        Date reverseOn = rs.getDate("auto_reverse_on");
        return new Header(rs.getObject("manual_journal_id", UUID.class), rs.getString("status"), rs.getString("period"),
            rs.getString("currency"), rs.getString("title"), rs.getString("reason"), rs.getString("reason_code"),
            rs.getString("template_id"), rs.getObject("reverses_journal_id", UUID.class),
            reverseOn == null ? null : reverseOn.toLocalDate(),
            refs == null ? List.of() : new ArrayList<>(Arrays.asList((String[]) refs.getArray())),
            rs.getString("preparer"), instant(rs, "prepared_at"), instant(rs, "submitted_at"), rs.getString("decided_by"),
            instant(rs, "decided_at"), rs.getString("decision_reason"), rs.getObject("journal_entry_id", UUID.class));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static String currencyOf(ManualJournalInput in) {
        return in.currency() == null || in.currency().isBlank() ? "TZS" : in.currency();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String trim(String s, int max) {
        String v = blankToNull(s);
        return v == null || v.length() <= max ? v : v.substring(0, max);
    }
}
