package tz.co.nlolo.lifeplatform.billing.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.billing.infrastructure.PolicyBalanceRepository;

import java.util.UUID;

/**
 * Pays premiums from policy balances as they fall due (money movement step 2, 2026-11-04).
 *
 * <p>Invoices are raised a year ahead, so a balance does not pay them the day money lands -- that would release next
 * year's commission and paid-to date early. It waits for each premium's due date, and this drain is what comes back
 * for it: {@code billing.balances_due_for_allocation()} names the policies with a balance and a premium due on or
 * before today (Dar es Salaam's day), and each is paid under its own tenant. The same SQL-selects, Java-acts split as
 * {@link InvoiceRollForward}, for the same reason: paying posts to the ledger, which a database job cannot do.
 *
 * <p>Exactly-once comes from the balance's row lock in {@code BillingApiImpl.payFromBalance}, so two instances are
 * safe: the second finds the premium already paid.
 */
@Component
public class BalanceAllocationDrain {

    private static final Logger log = LoggerFactory.getLogger(BalanceAllocationDrain.class);

    private final PolicyBalanceRepository policyBalanceRepository;
    private final BillingApiImpl billingApiImpl;

    public BalanceAllocationDrain(PolicyBalanceRepository policyBalanceRepository, BillingApiImpl billingApiImpl) {
        this.policyBalanceRepository = policyBalanceRepository;
        this.billingApiImpl = billingApiImpl;
    }

    /** Hourly by default, first run one interval after startup -- the roll-forward's cadence and reasoning. */
    @Scheduled(fixedDelayString = "${billing.balance-allocation-interval-ms:3600000}",
        initialDelayString = "${billing.balance-allocation-interval-ms:3600000}")
    public void payWhatHasFallenDue() {
        for (Object[] due : policyBalanceRepository.findDueForAllocationAcrossTenants()) {
            payOne((UUID) due[0], (String) due[1]);
        }
    }

    /** One policy, under its own tenant, set first and around everything. One failure must not stop the queue. */
    void payOne(UUID tenantId, String policyNumber) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenantId);
        try {
            billingApiImpl.payFromBalance(policyNumber);
        } catch (Exception e) {
            log.error("Failed to pay due premiums from the balance of policy {} in tenant {}", policyNumber, tenantId, e);
        } finally {
            if (previous != null) {
                TenantContext.set(previous);
            } else {
                TenantContext.clear();
            }
        }
    }
}
