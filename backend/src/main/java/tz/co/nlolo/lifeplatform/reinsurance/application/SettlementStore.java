package tz.co.nlolo.lifeplatform.reinsurance.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.reinsurance.api.SettlementView;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** reinsurance.settlement (V11, 2026-11-08): the movements settling an approved statement. */
@Component
class SettlementStore {

    /** Statuses that hold money against the statement: waiting, posted, or on its way out. */
    static final String COUNTED = "('AWAITING_APPROVAL', 'POSTED', 'REQUESTED', 'PAID')";

    record Row(UUID id, UUID statementId, UUID treatyId, String kind, BigDecimal amount, String currency, String method,
               String reference, java.time.LocalDate receivedOn, String payeeBank, String payeeBranch,
               String payeeAccountName, String payeeAccountNumber, String note, String status, String recordedBy,
               java.time.Instant recordedAt, String decidedBy, java.time.Instant decidedAt, String decisionReason,
               boolean actedAlone) {}

    private final JdbcTemplate jdbc;

    SettlementStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID tenantId, UUID id, UUID statementId, UUID treatyId, String currency, SettlementView.NewSettlement in,
                String by) {
        jdbc.update("INSERT INTO reinsurance.settlement (settlement_id, tenant_id, statement_id, treaty_id, kind, amount,"
                + " currency, method, reference, received_on, payee_bank, payee_branch, payee_account_name,"
                + " payee_account_number, note, status, recorded_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                + " 'AWAITING_APPROVAL', ?)",
            id, tenantId, statementId, treatyId, in.kind(), in.amount(), currency, in.method(), in.reference(),
            in.receivedOn() == null ? null : Date.valueOf(in.receivedOn()), in.payeeBank(), in.payeeBranch(),
            in.payeeAccountName(), in.payeeAccountNumber(), in.note(), by);
    }

    /** What the statement's settlements of one kind hold, of those not refused or failed. */
    BigDecimal counted(UUID tenantId, UUID statementId, String kind) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM reinsurance.settlement WHERE tenant_id = ?"
            + " AND statement_id = ? AND kind = ? AND status IN " + COUNTED, BigDecimal.class, tenantId, statementId, kind);
    }

    Optional<Row> get(UUID tenantId, UUID id) {
        return jdbc.query("SELECT * FROM reinsurance.settlement WHERE tenant_id = ? AND settlement_id = ?",
            SettlementStore::row, tenantId, id).stream().findFirst();
    }

    List<Row> ofStatement(UUID tenantId, UUID statementId) {
        return jdbc.query("SELECT * FROM reinsurance.settlement WHERE tenant_id = ? AND statement_id = ?"
            + " ORDER BY recorded_at DESC", SettlementStore::row, tenantId, statementId);
    }

    List<Row> waiting(UUID tenantId) {
        return jdbc.query("SELECT * FROM reinsurance.settlement WHERE tenant_id = ? AND status = 'AWAITING_APPROVAL'"
            + " ORDER BY recorded_at", SettlementStore::row, tenantId);
    }

    /** Decided once: only a waiting settlement moves, so two people deciding at once cannot both win. */
    boolean decide(UUID tenantId, UUID id, String status, String by, String reason) {
        return jdbc.update("UPDATE reinsurance.settlement SET status = ?, decided_by = ?, decided_at = now(),"
                + " decision_reason = ?, acted_alone = (recorded_by = ?)"
                + " WHERE tenant_id = ? AND settlement_id = ? AND status = 'AWAITING_APPROVAL'",
            status, by, reason, by, tenantId, id) == 1;
    }

    /** The EFT's outcome, once: REQUESTED becomes PAID or FAILED. */
    boolean settlePayout(UUID tenantId, UUID id, String status) {
        return jdbc.update("UPDATE reinsurance.settlement SET status = ? WHERE tenant_id = ? AND settlement_id = ?"
            + " AND status = 'REQUESTED'", status, tenantId, id) == 1;
    }

    private static Row row(ResultSet rs, int i) throws SQLException {
        Date received = rs.getDate("received_on");
        Timestamp decided = rs.getTimestamp("decided_at");
        return new Row(rs.getObject("settlement_id", UUID.class), rs.getObject("statement_id", UUID.class),
            rs.getObject("treaty_id", UUID.class), rs.getString("kind"), rs.getBigDecimal("amount"),
            rs.getString("currency").trim(), rs.getString("method"), rs.getString("reference"),
            received == null ? null : received.toLocalDate(), rs.getString("payee_bank"), rs.getString("payee_branch"),
            rs.getString("payee_account_name"), rs.getString("payee_account_number"), rs.getString("note"),
            rs.getString("status"), rs.getString("recorded_by"), rs.getTimestamp("recorded_at").toInstant(),
            rs.getString("decided_by"), decided == null ? null : decided.toInstant(), rs.getString("decision_reason"),
            rs.getBoolean("acted_alone"));
    }
}
