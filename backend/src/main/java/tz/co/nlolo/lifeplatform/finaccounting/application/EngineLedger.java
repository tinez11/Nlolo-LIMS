package tz.co.nlolo.lifeplatform.finaccounting.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.finaccounting.domain.EngineExtractSheets.BalanceRow;
import tz.co.nlolo.lifeplatform.finaccounting.domain.EngineExtractSheets.CashFlowRow;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The ledger as the IFRS 17 engine sees it (IFRS 17 I5a): every posting attributed to a group. A reinsurance line
 * (14xx, 6xxx) belongs to its reinsurance-held group -- found through the posting's source record
 * ({@link EngineReinsuranceGroups}) or, for the engine's own lines, on the posting -- and never to the policy group a
 * recovery also names; any other line belongs to the policy group on the posting. Lines with no group are not the
 * engine's.
 */
@Component
public class EngineLedger {

    /** Each posting with the group it belongs to, as {@code grp}; the caller adds its own WHERE on top. */
    static final String ATTRIBUTED = """
        SELECT CASE WHEN g.account_code LIKE '14%' OR g.account_code LIKE '6%'
                    THEN COALESCE(r.group_key, CASE WHEN g.ifrs17_group LIKE 'RI-%' THEN g.ifrs17_group END)
                    ELSE CASE WHEN g.ifrs17_group NOT LIKE 'RI-%' THEN g.ifrs17_group END
               END AS grp,
               g.account_code, g.direction, g.amount, g.currency, g.movement_type, g.period, g.policy_number,
               g.journal_entry_id
          FROM finaccounting.gl_posting g
          LEFT JOIN finaccounting.reinsurance_group_ref r
            ON r.tenant_id = g.tenant_id AND r.reference_type = g.reference_type AND r.reference = g.reference
         WHERE g.tenant_id = ?
        """;

    /** The accounts each kind of group is measured on: policy groups 21xx/22xx (not the 2190 reclass), RI groups 14xx. */
    static final String MEASURED = "((a.grp NOT LIKE 'RI-%' AND (a.account_code LIKE '21%' OR a.account_code LIKE '22%')"
        + " AND a.account_code <> '2190') OR (a.grp LIKE 'RI-%' AND a.account_code LIKE '14%'))";

    private final JdbcTemplate jdbc;

    EngineLedger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The period's actual cash flows by group, movement, account and side -- what the engine must use and leave out of
     * its future cash flows. The engine's own journals are not actuals and are left out, and nor are the year-end close's
     * (IFRS 17 I6). Policy groups also carry their
     * attributable expenses: allocated by P-19 (5210, 5215; IFRS 17 I5b) or expensed when incurred (5310).
     */
    List<CashFlowRow> cashFlows(UUID tenantId, String period) {
        return jdbc.query("SELECT a.grp, a.movement_type, a.account_code, a.direction, a.currency, sum(a.amount) AS amount"
                + " FROM (" + ATTRIBUTED + ") a"
                + " JOIN finaccounting.journal_entry j ON j.journal_entry_id = a.journal_entry_id"
                + " WHERE a.period = ? AND a.grp IS NOT NULL AND j.source_type <> 'ENGINE_RUN'"
                + " AND j.year_end_close_id IS NULL"
                + " AND ((a.grp NOT LIKE 'RI-%' AND (a.account_code LIKE '21%' OR a.account_code LIKE '22%'"
                + "      OR a.account_code IN ('5210','5215','5310')) AND a.account_code <> '2190')"
                + "   OR (a.grp LIKE 'RI-%' AND a.account_code IN ('1430','1431','1436','1420')))"
                + " GROUP BY a.grp, a.movement_type, a.account_code, a.direction, a.currency"
                + " ORDER BY a.grp, a.account_code, a.direction, a.movement_type",
            (rs, i) -> new CashFlowRow(rs.getString("grp"), rs.getString("movement_type"), rs.getString("account_code"),
                rs.getString("direction"), rs.getBigDecimal("amount"), trim(rs.getString("currency"))),
            tenantId, period);
    }

    /** Each group's measurement accounts, net Dr - Cr before the period (opening) and at its end (closing). */
    List<BalanceRow> balances(UUID tenantId, String period) {
        return jdbc.query("SELECT a.grp, a.account_code, a.currency,"
                + " sum(CASE WHEN a.period < ? THEN (CASE WHEN a.direction = 'DR' THEN a.amount ELSE -a.amount END) ELSE 0 END) AS opening,"
                + " sum(CASE WHEN a.direction = 'DR' THEN a.amount ELSE -a.amount END) AS closing"
                + " FROM (" + ATTRIBUTED + ") a"
                + " WHERE a.period <= ? AND a.grp IS NOT NULL AND " + MEASURED
                + " GROUP BY a.grp, a.account_code, a.currency ORDER BY a.grp, a.account_code",
            // A sum over no earlier rows comes back as a bare 0; the extract shows money with its two decimals.
            (rs, i) -> new BalanceRow(rs.getString("grp"), rs.getString("account_code"),
                rs.getBigDecimal("opening").setScale(2, java.math.RoundingMode.HALF_UP),
                rs.getBigDecimal("closing").setScale(2, java.math.RoundingMode.HALF_UP), trim(rs.getString("currency"))),
            period, tenantId, period);
    }

    /** One group's net Dr - Cr per account at the end of the period: what the reconciliation compares with the engine. */
    Map<String, BigDecimal> closingByAccount(UUID tenantId, String group, String period) {
        Map<String, BigDecimal> nets = new HashMap<>();
        jdbc.query("SELECT a.account_code, sum(CASE WHEN a.direction = 'DR' THEN a.amount ELSE -a.amount END) AS net"
                + " FROM (" + ATTRIBUTED + ") a WHERE a.period <= ? AND a.grp = ? GROUP BY a.account_code",
            rs -> {
                nets.put(rs.getString("account_code"), rs.getBigDecimal("net"));
            },
            tenantId, period, group);
        return nets;
    }

    /** Net Dr - Cr of one account by policy, at the end of the period: fund value (2131), investment component (2124). */
    Map<String, BigDecimal> netByPolicy(UUID tenantId, String account, String period) {
        Map<String, BigDecimal> nets = new HashMap<>();
        jdbc.query("SELECT policy_number, sum(CASE WHEN direction = 'DR' THEN amount ELSE -amount END) AS net"
                + " FROM finaccounting.gl_posting WHERE tenant_id = ? AND account_code = ? AND period <= ?"
                + " AND policy_number IS NOT NULL GROUP BY policy_number",
            rs -> {
                nets.put(rs.getString("policy_number"), rs.getBigDecimal("net"));
            },
            tenantId, account, period);
        return nets;
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
