package tz.co.nlolo.lifeplatform.billing.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.billing.infrastructure.FieldReceiptRepository;

import java.util.UUID;

/**
 * Agent cash the company never received (2026-11-11, the user's decision). Cash an agent records pays the customer's
 * premium at once; if it has not reached the company {@code billing.agent-cash-expiry-days} (14) days later, the deposit
 * is undone -- the premium is owed again, dunning resumes, the commission it earned is clawed back -- and the cash reads
 * EXPIRED. Money that arrives later still closes it, as an ordinary deposit.
 *
 * <p>Hourly, first run one interval after startup, as {@link BalanceAllocationDrain}: SQL selects across tenants, Java
 * acts under each tenant, one receipt per transaction so one failure never stops the rest.
 */
@Component
public class AgentCashExpiry {

    private static final Logger log = LoggerFactory.getLogger(AgentCashExpiry.class);

    private final FieldReceiptRepository fieldReceipts;
    private final DepositReversals reversals;
    private final BillingApiImpl billing;
    private final TransactionTemplate tx;
    private final int days;

    public AgentCashExpiry(FieldReceiptRepository fieldReceipts, DepositReversals reversals, BillingApiImpl billing,
                           PlatformTransactionManager transactionManager,
                           @Value("${billing.agent-cash-expiry-days:14}") int days) {
        this.fieldReceipts = fieldReceipts;
        this.reversals = reversals;
        this.billing = billing;
        this.tx = new TransactionTemplate(transactionManager);
        this.days = days;
    }

    @Scheduled(fixedDelayString = "${billing.agent-cash-expiry-interval-ms:3600000}",
        initialDelayString = "${billing.agent-cash-expiry-interval-ms:3600000}")
    public void undoUnreceived() {
        for (Object[] due : fieldReceipts.findPastExpiryAcrossTenants(days)) {
            expireOne((UUID) due[0], (UUID) due[1]);
        }
    }

    /** One agent's cash, under its own tenant: the deposit undone and the cash EXPIRED together, or neither. */
    void expireOne(UUID tenantId, UUID fieldReceiptId) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenantId);
        try {
            tx.executeWithoutResult(status -> {
                reversals.reverseUnreceivedAgentCash(fieldReceiptId, days);
                billing.expireFieldReceipt(fieldReceiptId);
            });
        } catch (Exception e) {
            log.error("Failed to undo agent cash {} in tenant {}", fieldReceiptId, tenantId, e);
        } finally {
            if (previous != null) {
                TenantContext.set(previous);
            } else {
                TenantContext.clear();
            }
        }
    }
}
