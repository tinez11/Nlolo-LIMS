package tz.co.nlolo.lifeplatform.finaccounting.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The year-end close's hold on December (IFRS 17 I6): a December whose year has anything to close -- class 4-8 or 3320
 * postings outside close journals -- locks only with a POSTED close of that year that is not stale. An empty year needs
 * no close, as an empty month needs no closing. JDBC only, so {@link AccountingPeriods} need not depend on
 * {@link YearEndCloses} (which depends on it).
 */
@Component
class YearEndLockGate {

    /** The accounts a year-end close clears: classes 4-8 and dividends declared. */
    static final String CLOSED_ACCOUNTS = "(substr(p.account_code, 1, 1) IN ('4','5','6','7','8') OR p.account_code = '3320')";

    private final JdbcTemplate jdbc;

    YearEndLockGate(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<String> blocking(UUID tenantId, String period) {
        if (!period.endsWith("-12")) {
            return Optional.empty();
        }
        int year = Integer.parseInt(period.substring(0, 4));
        Integer open = jdbc.queryForObject("SELECT count(*) FROM finaccounting.gl_posting p JOIN finaccounting.journal_entry j"
                + " ON j.journal_entry_id = p.journal_entry_id WHERE p.tenant_id = ? AND p.period BETWEEN ? AND ?"
                + " AND j.year_end_close_id IS NULL AND " + CLOSED_ACCOUNTS,
            Integer.class, tenantId, year + "-01", year + "-12");
        if (open == null || open == 0) {
            return Optional.empty();
        }
        List<Timestamp> decided = jdbc.queryForList("SELECT decided_at FROM finaccounting.year_end_close"
            + " WHERE tenant_id = ? AND year = ? AND status = 'POSTED'", Timestamp.class, tenantId, year);
        if (decided.isEmpty()) {
            return Optional.of("Year-end close required: prepare and approve the close of " + year);
        }
        return stale(tenantId, year, decided.get(0))
            ? Optional.of("The close of " + year + " is stale: postings to classes 4-8 since it was approved;"
                + " prepare a new close")
            : Optional.empty();
    }

    /** Class 4-8 or 3320 postings in the year, outside close journals, made after the close was approved. */
    boolean stale(UUID tenantId, int year, Timestamp decidedAt) {
        Integer later = jdbc.queryForObject("SELECT count(*) FROM finaccounting.gl_posting p JOIN finaccounting.journal_entry j"
                + " ON j.journal_entry_id = p.journal_entry_id WHERE p.tenant_id = ? AND p.period BETWEEN ? AND ?"
                + " AND j.year_end_close_id IS NULL AND p.created_at > ? AND " + CLOSED_ACCOUNTS,
            Integer.class, tenantId, year + "-01", year + "-12", decidedAt);
        return later != null && later > 0;
    }
}
