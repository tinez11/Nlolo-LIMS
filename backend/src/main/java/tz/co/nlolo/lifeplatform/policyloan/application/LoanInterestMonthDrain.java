package tz.co.nlolo.lifeplatform.policyloan.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes each completed month's accrued interest per loan as {@code policyloan.LoanInterestAccrued} (IFRS 17 I3b,
 * posting guide E-02), so the ledger accrues it into 2126 against 2121 (LN_INT). The interest itself is accrued daily
 * in the loan's own ledger by {@code accrue_loan_interest()}; this only tells the rest of the platform, once per loan
 * and month (policyloan V8 records which have been told, in the same transaction as the publish).
 *
 * <p>Months by the calendar in Dar es Salaam, never UTC. Hourly, like accumulation's month-end drain.
 */
@Component
public class LoanInterestMonthDrain {

    private static final Logger log = LoggerFactory.getLogger(LoanInterestMonthDrain.class);
    private static final ZoneId CIVIL = ZoneId.of("Africa/Dar_es_Salaam");

    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate requiresNew;

    public LoanInterestMonthDrain(JdbcTemplate jdbc, ApplicationEventPublisher events,
                                  PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.events = events;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelayString = "${policyloan.interest-month-interval-ms:3600000}",
        initialDelayString = "${policyloan.interest-month-interval-ms:3600000}")
    public void drain() {
        drain(LocalDate.now(CIVIL).withDayOfMonth(1));
    }

    /** Publishes every month before {@code monthStart} not yet published. */
    public void drain(LocalDate monthStart) {
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT * FROM policyloan.loan_interest_months_to_publish(?)", Date.valueOf(monthStart))) {
            UUID tenantId = (UUID) row.get("tenant_id");
            UUID previous = TenantContext.getOrNull();
            TenantContext.set(tenantId);
            try {
                requiresNew.executeWithoutResult(status -> publish(tenantId, row));
            } catch (Exception e) {
                // One loan's failure must not cost every other its month.
                log.error("Could not publish {}'s interest for loan {} in tenant {}", row.get("period"),
                    row.get("loan_id"), tenantId, e);
            } finally {
                if (previous != null) TenantContext.set(previous); else TenantContext.clear();
            }
        }
    }

    private void publish(UUID tenantId, Map<String, Object> row) {
        UUID loanId = (UUID) row.get("loan_id");
        String period = (String) row.get("period");
        BigDecimal amount = (BigDecimal) row.get("amount");
        int inserted = jdbc.update("INSERT INTO policyloan.interest_month_published (tenant_id, loan_id, period, amount)"
            + " VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING", tenantId, loanId, period, amount);
        if (inserted == 0 || amount.signum() <= 0) {
            return;
        }
        events.publishEvent(DomainEventEnvelope.of("policyloan.LoanInterestAccrued", tenantId, Map.of(
            "loanId", loanId.toString(), "policyNumber", row.get("policy_number"), "period", period,
            "amount", Map.of("amount", amount.toPlainString(), "currencyCode", String.valueOf(row.get("currency")).trim()))));
    }
}
