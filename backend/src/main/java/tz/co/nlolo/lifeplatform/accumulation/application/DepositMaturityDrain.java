package tz.co.nlolo.lifeplatform.accumulation.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.accumulation.infrastructure.DepositPeriodRepository;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Matures every deposit term whose date has come, across tenants, each under its own -- MonthEndDrain's
 * shape and reasoning: maturity publishes events (the payout, the policy's matured), so it is Java,
 * and only the selection is SQL ({@code deposits_due()}). Hourly by default; the {@code local}
 * profile runs it every ten seconds for the e2e suite.
 */
@Component
public class DepositMaturityDrain {

    private static final Logger log = LoggerFactory.getLogger(DepositMaturityDrain.class);

    private final DepositPeriodRepository periods;
    private final Deposits deposits;

    public DepositMaturityDrain(DepositPeriodRepository periods, Deposits deposits) {
        this.periods = periods;
        this.deposits = deposits;
    }

    @Scheduled(fixedDelayString = "${accumulation.deposit-maturity-interval-ms:3600000}",
        initialDelayString = "${accumulation.deposit-maturity-interval-ms:3600000}")
    public void drain() {
        for (Object[] row : periods.findDueAcrossTenants()) {
            matureOne((String) row[0], (UUID) row[1]);
        }
    }

    void matureOne(String policyNumber, UUID tenantId) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenantId);
        try {
            deposits.mature(policyNumber, LocalDate.now());
        } catch (Exception e) {
            // One deposit's failure must not cost every other its maturity.
            log.error("Deposit maturity failed for policy {} in tenant {}", policyNumber, tenantId, e);
        } finally {
            if (previous != null) TenantContext.set(previous); else TenantContext.clear();
        }
    }
}
