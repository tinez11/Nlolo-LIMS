package tz.co.nlolo.lifeplatform.accumulation.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.accumulation.infrastructure.TermAccountRepository;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Matures term accounts whose day has come (2026-10-21). {@link DepositMaturityDrain}'s shape and reasoning: maturity
 * publishes events (the payout, the policy's matured), so it is Java, and only the selection is SQL
 * ({@code term_accounts_due()}). Hourly by default; the {@code local} profile runs it every ten seconds for the e2e
 * suite, as it does the deposit drain.
 */
@Component
public class TermAccountMaturityDrain {

    private static final Logger log = LoggerFactory.getLogger(TermAccountMaturityDrain.class);

    private final TermAccountRepository termAccounts;
    private final AccumulationApiImpl api;

    public TermAccountMaturityDrain(TermAccountRepository termAccounts, AccumulationApiImpl api) {
        this.termAccounts = termAccounts;
        this.api = api;
    }

    @Scheduled(fixedDelayString = "${accumulation.term-account-maturity-interval-ms:3600000}",
        initialDelayString = "${accumulation.term-account-maturity-interval-ms:3600000}")
    public void drain() {
        for (Object[] row : termAccounts.findDueAcrossTenants()) {
            matureOne((String) row[0], (UUID) row[1]);
        }
    }

    void matureOne(String policyNumber, UUID tenantId) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenantId);
        try {
            api.matureTermAccount(policyNumber, LocalDate.now());
        } catch (Exception e) {
            // One account's failure must not cost every other its maturity.
            log.error("Term account maturity failed for policy {} in tenant {}", policyNumber, tenantId, e);
        } finally {
            if (previous != null) TenantContext.set(previous); else TenantContext.clear();
        }
    }
}
