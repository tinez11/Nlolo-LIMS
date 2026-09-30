package tz.co.nlolo.lifeplatform.billing.application;

import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.billing.infrastructure.BillingScheduleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Keeps regular-premium billing running past the first year.
 *
 * <p>Invoices are pre-created a year ahead at issue. Nothing extended them, so every monthly,
 * quarterly and annual policy stopped being billed after twelve months -- the defect this closes
 * (product step 0, D1). This drain finds schedules whose pre-created invoices are running low and
 * raises the next batch, up to the end of the contract.
 *
 * <p><b>Why a Spring {@code @Scheduled} rather than pg_cron</b>: raising an invoice publishes
 * {@code billing.PremiumInvoiceGenerated}, which finaccounting consumes to post the premium
 * receivable. A pg_cron INSERT would bill the customer while the ledger stayed silent. So the
 * selection is SQL ({@code billing.schedules_due_for_invoicing()}) and the raising is Java -- the
 * same split {@code communication.OfferReminderDispatcher} and {@code policy.CoverExpiryDrain} use.
 *
 * <p>Exactly-once comes from a row lock, not from running in one place: {@code rollForward} takes a
 * {@code PESSIMISTIC_WRITE} on the schedule, so two instances draining the same list serialise and
 * the second finds the due date already advanced. Two instances is therefore safe.
 */
@Component
public class InvoiceRollForward {

    private static final Logger log = LoggerFactory.getLogger(InvoiceRollForward.class);

    private final BillingScheduleRepository billingScheduleRepository;
    private final BillingApiImpl billingApiImpl;

    /**
     * How many months ahead counts as "running low". A schedule is rolled forward when its next due
     * date is within this window; generateInvoicesAhead advances the due date about a year on each
     * run, so two months of window leaves generous slack before the pre-created invoices run out.
     */
    @Value("${billing.roll-forward-horizon-months:2}")
    private int horizonMonths;

    public InvoiceRollForward(BillingScheduleRepository billingScheduleRepository, BillingApiImpl billingApiImpl) {
        this.billingScheduleRepository = billingScheduleRepository;
        this.billingApiImpl = billingApiImpl;
    }

    /**
     * Hourly by default, first run one interval after startup. The window is measured in months, so
     * an hourly run is far more often than it needs to be; it is cheap (an indexed read returning
     * nothing almost always) and survives a missed run without a gap in anyone's billing. The
     * initial delay keeps it from firing inside a short-lived test context, where it would roll
     * schedules forward under other tests' invoice-count assertions.
     */
    @Scheduled(fixedDelayString = "${billing.roll-forward-interval-ms:3600000}",
        initialDelayString = "${billing.roll-forward-interval-ms:3600000}")
    public void rollSchedulesForward() {
        for (Object[] due : billingScheduleRepository.findDueForInvoicingAcrossTenants(horizonMonths)) {
            UUID scheduleId = (UUID) due[0];
            UUID tenantId = (UUID) due[1];
            rollOne(scheduleId, tenantId);
        }
    }

    /**
     * Roll one schedule forward under its own tenant context, set FIRST and around everything so
     * the locked read and the writes run under RLS against exactly this tenant's rows even though
     * the id arrived from a cross-tenant lookup. One failing schedule must not stop the queue.
     */
    void rollOne(UUID scheduleId, UUID tenantId) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenantId);
        try {
            billingApiImpl.rollForward(scheduleId);
        } catch (Exception e) {
            log.error("Failed to roll billing schedule {} forward in tenant {}", scheduleId, tenantId, e);
        } finally {
            if (previous != null) {
                TenantContext.set(previous);
            } else {
                TenantContext.clear();
            }
        }
    }
}
