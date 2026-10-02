package tz.co.nlolo.lifeplatform.accumulation.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.accumulation.infrastructure.AccountRepository;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Posts each completed month's interest and fee, across every tenant, each under its own.
 *
 * <p>A Spring {@code @Scheduled} rather than pg_cron for {@code PayoutDueDrain}'s reason: exhaustion
 * lapses a policy, and only a published event reaches billing, benefitpayout and audit. Selection is
 * SQL ({@code accounts_due_month_end()}), the posting is Java. Exactly-once comes from the posting
 * index, so two instances draining the same list post each month once. Hourly by default; the
 * {@code local} profile runs it every ten seconds for the e2e suite.
 */
@Component
public class MonthEndDrain {

    private static final Logger log = LoggerFactory.getLogger(MonthEndDrain.class);

    private final AccountRepository accounts;
    private final AccumulationApiImpl api;

    public MonthEndDrain(AccountRepository accounts, AccumulationApiImpl api) {
        this.accounts = accounts;
        this.api = api;
    }

    @Scheduled(fixedDelayString = "${accumulation.month-end-interval-ms:3600000}",
        initialDelayString = "${accumulation.month-end-interval-ms:3600000}")
    public void drain() {
        for (Object[] row : accounts.findDueMonthEndAcrossTenants()) {
            postOne((String) row[0], (UUID) row[1]);
        }
    }

    void postOne(String policyNumber, UUID tenantId) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenantId);
        try {
            api.postMonthEnds(policyNumber, LocalDate.now());
        } catch (Exception e) {
            // One account's failure must not cost every other its interest.
            log.error("Month-end posting failed for policy {} in tenant {}", policyNumber, tenantId, e);
        } finally {
            if (previous != null) TenantContext.set(previous); else TenantContext.clear();
        }
    }
}
