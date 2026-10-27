package tz.co.nlolo.lifeplatform.payment.application;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.payment.api.PaymentRecordView;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The payments register (2026-10-27): both ledgers, money in and money out, as one list, newest first.
 *
 * <p>Finance could read a payment only by its id. A collection the rail left IN_DOUBT, or a payout it FAILED, sat in
 * its table with no screen anywhere listing it -- the one state an operator most needs to see. The register reads the
 * two tables as they are, through a UNION, so nothing is copied and nothing can disagree.
 */
@Component
class PaymentRegister {

    /** What a person asks for, in the words of the work, mapped to each ledger's own statuses. */
    static final Map<String, List<String>> STATES = Map.of(
        "attention", List.of("IN_DOUBT", "FAILED"),
        "waiting", List.of("PENDING", "AWAITING_EXECUTION"),
        "done", List.of("CONFIRMED", "COMPLETED"),
        "all", List.of("PENDING", "AWAITING_EXECUTION", "IN_DOUBT", "CONFIRMED", "COMPLETED", "FAILED"));

    private static final String ROWS = """
        SELECT * FROM (
            SELECT payment_transaction_id AS id, 'IN' AS direction, purpose, 'MOBILE_MONEY' AS method, status, amount,
                   currency, payer_ref AS counterparty, gateway_reference AS reference, source_ref, policy_number,
                   created_at, CAST(NULL AS VARCHAR) AS executed_by, CAST(NULL AS TIMESTAMPTZ) AS executed_at
              FROM payment.payment_transaction WHERE tenant_id = :tenant
            UNION ALL
            SELECT disbursement_id, 'OUT', purpose, method, status, amount, currency, payee_ref, gateway_reference,
                   source_ref, policy_number, created_at, executed_by, executed_at
              FROM payment.disbursement_instruction WHERE tenant_id = :tenant
        ) p
        WHERE (CAST(:direction AS VARCHAR) IS NULL OR direction = :direction)
          AND status IN (:statuses)
          AND (CAST(:policy AS VARCHAR) IS NULL OR policy_number = :policy)
        """;

    private final NamedParameterJdbcTemplate jdbc;

    PaymentRegister(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    Page<PaymentRecordView> search(String direction, String state, String policyNumber, Pageable pageable) {
        List<String> statuses = STATES.getOrDefault(state == null ? "all" : state, STATES.get("all"));
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("tenant", TenantContext.get())
            .addValue("direction", direction == null || direction.isBlank() ? null : direction)
            .addValue("statuses", statuses)
            .addValue("policy", policyNumber == null || policyNumber.isBlank() ? null : policyNumber.trim())
            .addValue("size", pageable.getPageSize())
            .addValue("offset", pageable.getOffset());
        Long total = jdbc.queryForObject("SELECT count(*) FROM (" + ROWS + ") counted", params, Long.class);
        List<PaymentRecordView> rows = jdbc.query(ROWS + " ORDER BY created_at DESC LIMIT :size OFFSET :offset", params,
            (rs, i) -> new PaymentRecordView(rs.getObject("id", UUID.class), rs.getString("direction"),
                rs.getString("purpose"), rs.getString("method"), rs.getString("status"), rs.getBigDecimal("amount"),
                rs.getString("currency").trim(), rs.getString("counterparty"), rs.getString("reference"),
                rs.getString("source_ref"), rs.getString("policy_number"), instant(rs.getTimestamp("created_at")),
                rs.getString("executed_by"), instant(rs.getTimestamp("executed_at"))));
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    private static java.time.Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
