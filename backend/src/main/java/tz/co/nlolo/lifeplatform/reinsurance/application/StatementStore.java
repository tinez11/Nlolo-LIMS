package tz.co.nlolo.lifeplatform.reinsurance.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.reinsurance.domain.StatementCalculator.Quarter;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The store of quarterly statements and what each settles (IFRS 17 I3d, reinsurance V7). JDBC under the caller's
 * tenant and transaction; RLS scopes every read. Every state change is one guarded UPDATE ("... AND status = ?"), so
 * two people acting on one statement at once cannot both win.
 */
@Component
class StatementStore {   // not "Statements": unitlinked has a bean of that name, and Spring names beans by class

    private static final ZoneId CIVIL = ZoneId.of("Africa/Dar_es_Salaam");

    record Row(UUID id, UUID treatyId, String quarter, String currency, String status, BigDecimal premium,
               BigDecimal commission, BigDecimal recoveries, BigDecimal fundsWithheld, BigDecimal profitCommission,
               String reason, List<String> documentRefs, String preparer, Instant preparedAt, Instant submittedAt,
               String decidedBy, Instant decidedAt, String decisionReason) {}

    record Item(String type, UUID id, String label, BigDecimal amount) {}

    record BordereauRow(UUID id, String period, BigDecimal premium, BigDecimal commission) {}

    record RecoveryRow(UUID id, UUID claimId, BigDecimal amount) {}

    private final JdbcTemplate jdbc;

    StatementStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- what a quarter holds ------------------------------------------------------------------------------------

    /** The treaty's bordereaux for the quarter's months. */
    List<BordereauRow> bordereauxOf(UUID tenantId, UUID treatyId, Quarter quarter) {
        List<String> months = quarter.months().stream().map(Object::toString).toList();
        return jdbc.query("SELECT bordereau_id, period, premium, commission FROM reinsurance.bordereau"
                + " WHERE tenant_id = ? AND treaty_id = ? AND period IN (?, ?, ?) ORDER BY period",
            (rs, i) -> new BordereauRow(rs.getObject("bordereau_id", UUID.class), rs.getString("period"),
                rs.getBigDecimal("premium"), rs.getBigDecimal("commission")),
            tenantId, treatyId, months.get(0), months.get(1), months.get(2));
    }

    /**
     * The treaty's recoveries recorded in the quarter (civil dates) that posted 1420 (not legacy) and are not on a live
     * statement already.
     */
    List<RecoveryRow> recoveriesOf(UUID tenantId, UUID treatyId, Quarter quarter) {
        return jdbc.query("SELECT r.recovery_id, r.claim_id, r.recoverable_amount FROM reinsurance.claim_recovery r"
                + " WHERE r.tenant_id = ? AND r.treaty_id = ? AND NOT r.legacy AND r.created_at >= ? AND r.created_at < ?"
                + " AND NOT EXISTS (SELECT 1 FROM reinsurance.statement_item i WHERE i.tenant_id = r.tenant_id"
                + "   AND i.item_type = 'RECOVERY' AND i.item_id = r.recovery_id AND i.live)"
                + " ORDER BY r.created_at",
            (rs, i) -> new RecoveryRow(rs.getObject("recovery_id", UUID.class), rs.getObject("claim_id", UUID.class),
                rs.getBigDecimal("recoverable_amount")),
            tenantId, treatyId, civilStart(quarter.start()), civilStart(quarter.endExclusive()));
    }

    /** The live (not rejected) statement of the treaty's quarter, if any. */
    Optional<Row> liveFor(UUID tenantId, UUID treatyId, Quarter quarter) {
        return jdbc.query("SELECT * FROM reinsurance.statement WHERE tenant_id = ? AND treaty_id = ? AND quarter = ?"
            + " AND status <> 'REJECTED'", this::row, tenantId, treatyId, quarter.toString()).stream().findFirst();
    }

    // ---- writes ---------------------------------------------------------------------------------------------------

    UUID insert(UUID tenantId, UUID treatyId, Quarter quarter, String currency, BigDecimal premium,
                BigDecimal commission, BigDecimal recoveries, String preparer) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO reinsurance.statement (statement_id, tenant_id, treaty_id, quarter, currency, status,"
                + " premium, commission, recoveries, preparer) VALUES (?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?)",
            id, tenantId, treatyId, quarter.toString(), currency, premium, commission, recoveries, preparer);
        return id;
    }

    void insertItem(UUID tenantId, UUID statementId, String type, UUID itemId) {
        jdbc.update("INSERT INTO reinsurance.statement_item (statement_id, tenant_id, item_type, item_id)"
            + " VALUES (?, ?, ?, ?)", statementId, tenantId, type, itemId);
    }

    boolean updateDraft(UUID tenantId, UUID id, BigDecimal withheld, BigDecimal profitCommission, String reason) {
        return jdbc.update("UPDATE reinsurance.statement SET funds_withheld = ?, profit_commission = ?, reason = ?,"
                + " version = version + 1 WHERE tenant_id = ? AND statement_id = ? AND status = 'DRAFT'",
            withheld, profitCommission, reason, tenantId, id) == 1;
    }

    /** @return true when the statement was in {@code from} and is now in {@code to} */
    boolean move(UUID tenantId, UUID id, String from, String to) {
        String stamp = switch (to) {
            case "SUBMITTED" -> ", submitted_at = now()";
            case "DRAFT" -> ", submitted_at = NULL";
            default -> "";
        };
        return jdbc.update("UPDATE reinsurance.statement SET status = ?" + stamp + ", version = version + 1"
            + " WHERE tenant_id = ? AND statement_id = ? AND status = ?", to, tenantId, id, from) == 1;
    }

    boolean decide(UUID tenantId, UUID id, String status, String by, String reason) {
        return jdbc.update("UPDATE reinsurance.statement SET status = ?, decided_by = ?, decided_at = now(),"
                + " decision_reason = ?, version = version + 1"
                + " WHERE tenant_id = ? AND statement_id = ? AND status = 'SUBMITTED'",
            status, by, reason, tenantId, id) == 1;
    }

    /** A rejected statement settles nothing: its bordereaux and recoveries are free for the quarter's next one. */
    void release(UUID tenantId, UUID id) {
        jdbc.update("UPDATE reinsurance.statement_item SET live = false WHERE tenant_id = ? AND statement_id = ?",
            tenantId, id);
    }

    void addDocument(UUID tenantId, UUID id, String ref) {
        jdbc.update("UPDATE reinsurance.statement SET document_refs = array_append(document_refs, ?)"
            + " WHERE tenant_id = ? AND statement_id = ? AND status = 'DRAFT' AND NOT (? = ANY(document_refs))",
            ref, tenantId, id, ref);
    }

    // ---- reads ----------------------------------------------------------------------------------------------------

    Optional<Row> find(UUID tenantId, UUID id) {
        return jdbc.query("SELECT * FROM reinsurance.statement WHERE tenant_id = ? AND statement_id = ?", this::row,
            tenantId, id).stream().findFirst();
    }

    List<Row> list(UUID tenantId, String status, UUID treatyId) {
        return jdbc.query("SELECT * FROM reinsurance.statement WHERE tenant_id = ?"
                + " AND (?::text IS NULL OR status = ?) AND (?::uuid IS NULL OR treaty_id = ?)"
                + " ORDER BY prepared_at DESC LIMIT 500",
            this::row, tenantId, status, status, treatyId, treatyId);
    }

    /** What the statement settles: its bordereaux by month, then its recoveries by claim. */
    List<Item> items(UUID tenantId, UUID id) {
        List<Item> items = new ArrayList<>(jdbc.query("SELECT b.bordereau_id, b.period, b.premium FROM reinsurance.statement_item i"
                + " JOIN reinsurance.bordereau b ON b.bordereau_id = i.item_id"
                + " WHERE i.tenant_id = ? AND i.statement_id = ? AND i.item_type = 'BORDEREAU' ORDER BY b.period",
            (rs, n) -> new Item("BORDEREAU", rs.getObject("bordereau_id", UUID.class), "Bordereau " + rs.getString("period"),
                rs.getBigDecimal("premium")),
            tenantId, id));
        items.addAll(jdbc.query("SELECT r.recovery_id, r.claim_id, r.recoverable_amount FROM reinsurance.statement_item i"
                + " JOIN reinsurance.claim_recovery r ON r.recovery_id = i.item_id"
                + " WHERE i.tenant_id = ? AND i.statement_id = ? AND i.item_type = 'RECOVERY' ORDER BY r.created_at",
            (rs, n) -> new Item("RECOVERY", rs.getObject("recovery_id", UUID.class),
                "Recovery on claim " + rs.getObject("claim_id", UUID.class), rs.getBigDecimal("recoverable_amount")),
            tenantId, id));
        return items;
    }

    private Row row(ResultSet rs, int i) throws SQLException {
        Array refs = rs.getArray("document_refs");
        return new Row(rs.getObject("statement_id", UUID.class), rs.getObject("treaty_id", UUID.class),
            rs.getString("quarter"), rs.getString("currency").trim(), rs.getString("status"), rs.getBigDecimal("premium"),
            rs.getBigDecimal("commission"), rs.getBigDecimal("recoveries"), rs.getBigDecimal("funds_withheld"),
            rs.getBigDecimal("profit_commission"), rs.getString("reason"),
            refs == null ? List.of() : new ArrayList<>(Arrays.asList((String[]) refs.getArray())),
            rs.getString("preparer"), instant(rs, "prepared_at"), instant(rs, "submitted_at"), rs.getString("decided_by"),
            instant(rs, "decided_at"), rs.getString("decision_reason"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static Timestamp civilStart(LocalDate day) {
        return Timestamp.from(day.atStartOfDay(CIVIL).toInstant());
    }
}
