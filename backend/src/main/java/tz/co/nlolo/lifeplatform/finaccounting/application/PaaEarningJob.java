package tz.co.nlolo.lifeplatform.finaccounting.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.finaccounting.api.JournalSource;
import tz.co.nlolo.lifeplatform.finaccounting.domain.PostingFacts;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Earns PAA premium month by month (IFRS 17 I3a, guide I-03): Dr 2141 LRC (PAA) / Cr 4160 Insurance revenue, through
 * the posting rules like every other journal, as a SYSTEM journal per policy and month.
 *
 * <p>Each completed month (by the calendar in Dar es Salaam, never UTC) earns, from each schedule row, its cover
 * elapsed by the month's end: cumulatively, so what a row has earned by any month end is exactly its net premium times
 * the share of its cover gone by -- the whole of it once the cover has ended. A re-run earns nothing (the target is
 * already reached, and the journal's (event, ref) key would refuse a second one). A month whose period is LOCKED is
 * passed over: the next open month's cumulative earning carries it. A month that cannot post stops that policy's run,
 * so a later month never earns ahead of an earlier one still waiting in the unposted-event queue.
 *
 * <p>Hourly, like accumulation's month-end drain; the local profile runs it more often for the e2e suite.
 */
@Component
public class PaaEarningJob {

    static final String EVENT = "finaccounting.PaaRevenueEarned";
    private static final ZoneId CIVIL = ZoneId.of("Africa/Dar_es_Salaam");
    private static final String BY = "system:paa-earning";
    private static final Logger log = LoggerFactory.getLogger(PaaEarningJob.class);

    private final JdbcTemplate jdbc;
    private final PostingEngine engine;

    PaaEarningJob(JdbcTemplate jdbc, PostingEngine engine) {
        this.jdbc = jdbc;
        this.engine = engine;
    }

    @Scheduled(fixedDelayString = "${finaccounting.paa-earning-interval-ms:3600000}",
        initialDelayString = "${finaccounting.paa-earning-interval-ms:3600000}")
    public void drain() {
        LocalDate through = lastCompletedMonthEnd(LocalDate.now(CIVIL));
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT tenant_id, policy_number FROM finaccounting.paa_policies_to_earn(?)", Date.valueOf(through))) {
            UUID tenantId = (UUID) row.get("tenant_id");
            String policy = (String) row.get("policy_number");
            UUID previous = TenantContext.getOrNull();
            TenantContext.set(tenantId);
            try {
                earn(tenantId, policy, through);
            } catch (Exception e) {
                // One policy's failure must not cost every other its revenue.
                log.error("PAA earning failed for policy {} in tenant {}", policy, tenantId, e);
            } finally {
                if (previous != null) TenantContext.set(previous); else TenantContext.clear();
            }
        }
    }

    static LocalDate lastCompletedMonthEnd(LocalDate today) {
        return YearMonth.from(today).minusMonths(1).atEndOfMonth();
    }

    /** Earns one policy's schedule through {@code through}, month by month. The caller has set the tenant. */
    void earn(UUID tenantId, String policy, LocalDate through) {
        List<Map<String, Object>> rows = schedule(tenantId, policy, through);
        if (rows.isEmpty()) {
            return;
        }
        YearMonth month = rows.stream().map(PaaEarningJob::firstUnearnedMonth).min(YearMonth::compareTo).orElseThrow();
        for (; !month.isAfter(YearMonth.from(through)); month = month.plusMonths(1)) {
            if (locked(tenantId, month)) {
                continue;
            }
            if (!earnMonth(tenantId, policy, month)) {
                return;
            }
        }
        // Rows with nothing to earn in any month passed over are earned through it too, so the selection moves on.
        jdbc.update("UPDATE finaccounting.paa_earning SET earned_through = ? WHERE tenant_id = ? AND policy_number = ?"
                + " AND covers_from <= ? AND (earned_through IS NULL OR earned_through < ?)",
            Date.valueOf(through), tenantId, policy, Date.valueOf(through), Date.valueOf(through));
    }

    /** @return false when the month could not post, so the policy's later months wait for it */
    private boolean earnMonth(UUID tenantId, String policy, YearMonth month) {
        LocalDate monthEnd = month.atEndOfMonth();
        StringBuilder earnings = new StringBuilder();
        BigDecimal total = BigDecimal.ZERO;
        String currency = null;
        for (Map<String, Object> row : schedule(tenantId, policy, monthEnd)) {
            BigDecimal earn = earnedBy(row, monthEnd).subtract((BigDecimal) row.get("earned"));
            if (earn.signum() <= 0) {
                continue;
            }
            if (!earnings.isEmpty()) {
                earnings.append(';');
            }
            earnings.append(row.get("earning_id")).append('=').append(earn.toPlainString());
            total = total.add(earn);
            currency = (String) row.get("currency");
        }
        if (total.signum() == 0) {
            return true;
        }
        PostingFacts facts = new PostingFacts(EVENT, policy + ":" + month, policy, currency, monthEnd,
            Map.of(PostingFactsExtractor.AMOUNT, total), Map.of("earnings", earnings.toString(), "refType", "PAA_EARNING"));
        PostingEngine.Result result = engine.post(tenantId, facts, BY, JournalSource.SYSTEM);
        return result.outcome() == PostingEngine.Outcome.POSTED || result.outcome() == PostingEngine.Outcome.ALREADY_POSTED;
    }

    /**
     * What a row has earned by {@code monthEnd}: its net premium (less what was waived or credited) times the share of
     * its cover elapsed, days inclusive; all of it once the cover has ended.
     */
    static BigDecimal earnedBy(Map<String, Object> row, LocalDate monthEnd) {
        BigDecimal net = ((BigDecimal) row.get("amount")).subtract((BigDecimal) row.get("reduced"));
        LocalDate from = ((Date) row.get("covers_from")).toLocalDate();
        LocalDate to = ((Date) row.get("covers_to")).toLocalDate();
        if (monthEnd.isBefore(from)) {
            return BigDecimal.ZERO;
        }
        if (!monthEnd.isBefore(to)) {
            return net;
        }
        long elapsed = ChronoUnit.DAYS.between(from, monthEnd) + 1;
        long cover = ChronoUnit.DAYS.between(from, to) + 1;
        return net.multiply(BigDecimal.valueOf(elapsed)).divide(BigDecimal.valueOf(cover), 2, RoundingMode.HALF_EVEN);
    }

    private static YearMonth firstUnearnedMonth(Map<String, Object> row) {
        Object through = row.get("earned_through");
        return through == null ? YearMonth.from(((Date) row.get("covers_from")).toLocalDate())
            : YearMonth.from(((Date) through).toLocalDate()).plusMonths(1);
    }

    private List<Map<String, Object>> schedule(UUID tenantId, String policy, LocalDate through) {
        return jdbc.queryForList("SELECT * FROM finaccounting.paa_earning WHERE tenant_id = ? AND policy_number = ?"
                + " AND covers_from <= ? AND earned < amount - reduced ORDER BY invoice_ref, member_ref",
            tenantId, policy, Date.valueOf(through));
    }

    private boolean locked(UUID tenantId, YearMonth month) {
        List<String> status = jdbc.queryForList("SELECT status FROM finaccounting.accounting_period"
            + " WHERE tenant_id = ? AND period = ?", String.class, tenantId, month.toString());
        return !status.isEmpty() && "LOCKED".equals(status.get(0));
    }
}
