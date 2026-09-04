package tz.co.nlolo.lifeplatform.billing.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.billing.domain.ArrearsCase;
import tz.co.nlolo.lifeplatform.billing.domain.FieldReceipt;
import tz.co.nlolo.lifeplatform.billing.infrastructure.ArrearsCaseRepository;
import tz.co.nlolo.lifeplatform.billing.infrastructure.FieldReceiptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The Java half of the notification split: publishes domain events for arrears and field
 * receipts whose business state the pg_cron SQL sweep has already transitioned, but which
 * the application has not announced yet.
 *
 * <p>The split is deliberate and documented in the sweep's own SQL. {@code
 * billing.sweep_billing_state()} is SECURITY DEFINER and bypasses RLS by table ownership, so
 * it can open arrears cases and escalate {@code dunning_level} across every tenant on a
 * timer. It publishes nothing, because it is SQL and this platform's events are in-process
 * Spring events. So something in Java has to notice the state it left and announce it.
 *
 * <p><b>This lived as a public method on {@code BillingApiImpl} with zero callers.</b> It was
 * written, unit-tested by invoking the bean directly, and never placed on a request path --
 * so in a deployed environment {@code dunning_level} would climb to 5 in the database while
 * {@code billing.PremiumOverdue} and {@code billing.PolicyLapseRecommended} were never
 * published, and {@code PolicyLapseRecommendedEventListener} -- the mechanism
 * {@code Policy}'s own javadoc names for automatic lapse -- would never fire. A method the
 * tests prove works and nothing proves ever runs.
 *
 * <p><b>Its own bean, not a method on {@code BillingApiImpl}, and that is a correctness
 * requirement rather than tidiness.</b> The caller is {@code BillingApiImpl.listInvoices};
 * calling a {@code @Transactional} method on {@code this} is self-invocation, which does not
 * go through the Spring proxy, so the annotation is ignored. With no transaction there is no
 * commit, and {@code @TransactionalEventListener(AFTER_COMMIT)} silently drops every event
 * published inside it. This codebase has already been bitten by exactly that failure -- rows
 * saved while the audit journal never saw them -- and the failure is invisible: the sweep
 * would report a count and announce nothing.
 *
 * <p>Why an opportunistic per-request sweep rather than {@code @Scheduled}: a cross-tenant
 * scheduled sweep is incompatible with this platform's fail-closed RLS, because a background
 * thread carries no {@code TenantContext} and therefore sees zero rows on every protected
 * table, and there is no tenant-directory table to iterate. {@code PolicyApiImpl}'s stale
 * loan-reservation sweep is the same idiom, and was the working example this one should have
 * copied.
 */
@Component
public class ArrearsNotificationSweep {

    private static final Logger log = LoggerFactory.getLogger(ArrearsNotificationSweep.class);

    /**
     * Dunning level at which the platform stops asking and recommends lapse. Level 5 publishes
     * {@code PolicyLapseRecommended}, which {@code policy} consumes to lapse the contract;
     * every level below it publishes {@code PremiumOverdue}, which is a customer notification.
     */
    private static final int LAPSE_RECOMMENDATION_LEVEL = 5;

    private final ArrearsCaseRepository arrearsCaseRepository;
    private final FieldReceiptRepository fieldReceiptRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ArrearsNotificationSweep(ArrearsCaseRepository arrearsCaseRepository,
                                      FieldReceiptRepository fieldReceiptRepository,
                                      ApplicationEventPublisher eventPublisher) {
        this.arrearsCaseRepository = arrearsCaseRepository;
        this.fieldReceiptRepository = fieldReceiptRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Publishes what is owed for one tenant and returns how many events went out.
     *
     * <p>{@code REQUIRES_NEW} so a caller on a read path cannot be damaged by this. Under the
     * default propagation the sweep would join an ambient transaction, and then a failure here
     * -- swallowed by the caller to keep the read working -- would still leave that
     * transaction marked rollback-only and fail the read at commit anyway. A separate
     * transaction also means the events commit and fire on their own terms rather than waiting
     * on whatever the caller is doing.
     *
     * <p>Both loops are naturally idempotent through their own filters: the arrears query
     * selects only cases whose {@code dunningLevel} exceeds {@code lastNotifiedDunningLevel},
     * and marking closes that gap; the receipt query selects only those with a null
     * {@code notifiedOverdueAt}. Re-running immediately publishes nothing.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int publishPending(UUID tenantId) {
        int published = 0;

        for (ArrearsCase arrearsCase : arrearsCaseRepository
                .findByTenantIdAndResolvedAtIsNullAndDunningLevelGreaterThanLastNotifiedDunningLevel(tenantId)) {
            int level = arrearsCase.getDunningLevel();
            String eventType = level >= LAPSE_RECOMMENDATION_LEVEL
                ? "billing.PolicyLapseRecommended"
                : "billing.PremiumOverdue";
            eventPublisher.publishEvent(DomainEventEnvelope.of(eventType, tenantId,
                level >= LAPSE_RECOMMENDATION_LEVEL
                    ? Map.of("policyNumber", arrearsCase.getPolicyNumber(),
                             "invoiceId", arrearsCase.getInvoiceId(),
                             "recommendedAt", Instant.now().toString())
                    : Map.of("invoiceId", arrearsCase.getInvoiceId(),
                             "policyNumber", arrearsCase.getPolicyNumber(),
                             "dunningLevel", level)));
            arrearsCase.markNotified(level);
            arrearsCaseRepository.save(arrearsCase);
            published++;
        }

        for (FieldReceipt receipt : fieldReceiptRepository
                .findByTenantIdAndStatusAndNotifiedOverdueAtIsNull(tenantId, "RECONCILIATION_OVERDUE")) {
            eventPublisher.publishEvent(DomainEventEnvelope.of("billing.FieldReceiptReconciliationOverdue", tenantId,
                Map.of("receiptId", receipt.getReceiptId(),
                       "policyNumber", receipt.getPolicyNumber(),
                       "overdueSince", receipt.getCapturedAtServer().toString())));
            receipt.markNotifiedOverdue();
            fieldReceiptRepository.save(receipt);
            published++;
        }

        if (published > 0) {
            log.info("Published {} pending billing notification event(s) for tenant {}", published, tenantId);
        }
        return published;
    }
}
