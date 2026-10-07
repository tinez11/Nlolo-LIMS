package tz.co.nlolo.lifeplatform.finaccounting.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * What an engine run leaves standing in the way of locking its period (IFRS 17 I5a, user answer Q4): each figure of the
 * period's POSTED run whose difference from the ledger is more than rounding and has not been accepted -- and an expense
 * allocation still awaiting its decision (IFRS 17 I5b). Read straight from the tables so {@link AccountingPeriods} need
 * not depend on the engine cycle (which depends on it).
 */
@Component
class EngineLockGate {

    private final JdbcTemplate jdbc;

    EngineLockGate(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Why the period cannot lock yet, one line per unaccepted difference; empty when nothing blocks it. */
    List<String> blocking(UUID tenantId, String period) {
        List<String> blocking = new java.util.ArrayList<>(jdbc.query("SELECT allocation_id FROM"
                + " finaccounting.expense_allocation WHERE tenant_id = ? AND period = ? AND status = 'PREPARED'",
            (rs, i) -> "Expense allocation awaiting a decision; approve or reject it", tenantId, period));
        blocking.addAll(engineDifferences(tenantId, period));
        return blocking;
    }

    private List<String> engineDifferences(UUID tenantId, String period) {
        return jdbc.query("SELECT r.engine_reference, c.group_key, c.figure, c.difference, c.status"
                + " FROM finaccounting.engine_reconciliation c JOIN finaccounting.engine_run r ON r.run_id = c.run_id"
                + " WHERE c.tenant_id = ? AND r.period = ? AND r.status = 'POSTED' AND c.status IN ('EXCEPTION','EXPLAINED')"
                + " ORDER BY c.group_key, c.figure",
            (rs, i) -> "Engine run " + rs.getString("engine_reference") + ": " + rs.getString("group_key") + " "
                + rs.getString("figure") + " differs from the ledger by " + rs.getBigDecimal("difference").abs().toPlainString()
                + " TZS (" + rs.getString("status").toLowerCase() + "); explain and accept it, or post a replacement run",
            tenantId, period);
    }
}
