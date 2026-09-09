package tz.co.nlolo.lifeplatform.communication.application;

import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.communication.api.NotificationChannel;
import tz.co.nlolo.lifeplatform.communication.domain.NotificationDispatch;
import tz.co.nlolo.lifeplatform.communication.domain.NotificationTemplate;
import tz.co.nlolo.lifeplatform.communication.domain.TemplateRenderer;
import tz.co.nlolo.lifeplatform.communication.infrastructure.NotificationDispatchRepository;
import tz.co.nlolo.lifeplatform.communication.infrastructure.NotificationSender;
import tz.co.nlolo.lifeplatform.communication.infrastructure.NotificationTemplateRepository;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyDetailView;
import tz.co.nlolo.lifeplatform.refdata.api.ReferenceDataApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Sends the reminders {@code communication.sweep_offer_reminders()} queued.
 *
 * <p><b>A Spring {@code @Scheduled}, which this platform otherwise reserves for a narrow set of
 * jobs — and the distinction is real rather than a loophole.</b> The rule that scheduled work
 * belongs in pg_cron exists because a cross-tenant business-state transition must happen exactly
 * once, centrally, on a schedule no deployment topology can change: that is why the expiry sweep,
 * the billing sweep and the reminder SELECTION are all pg_cron. This is not that. Deciding who
 * gets reminded already happened in SQL; this drains the queue those decisions produced, and
 * draining twice is prevented by claiming rows rather than by running in only one place.
 *
 * <p>It exists at all because SQL cannot call an SMS aggregator or render a template.
 *
 * <p>Each row is claimed with a conditional UPDATE before anything is sent, so of two instances
 * reading the same PENDING row exactly one proceeds. The claim commits before the send, which
 * errs deliberately: a crash mid-send leaves the row claimed rather than PENDING, so a customer
 * misses one reminder instead of receiving a stream of them on every subsequent pass.
 *
 * <p>A {@link TransactionTemplate} rather than {@code @Transactional} on a private method: this
 * class calls its own send path in a loop, and a self-invoked {@code @Transactional} silently
 * bypasses the proxy and does nothing at all — a trap this codebase has already been caught by
 * once, where the row saved but its AFTER_COMMIT event never reached the audit log.
 */
@Component
public class OfferReminderDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OfferReminderDispatcher.class);
    private static final String TEMPLATE_KEY = "OFFER_CLOSING";
    /** See NotificationApiImpl: the schema carries a language per template, nothing per party. */
    private static final String DEFAULT_LANGUAGE = "sw";
    private static final long DEFAULT_REMINDER_DAYS = 7L;
    /** The nil uuid owns the platform default wording. See communication/V8 and NotificationApiImpl. */
    private static final UUID PLATFORM_DEFAULT_TENANT = new UUID(0L, 0L);

    private final NotificationDispatchRepository dispatchRepository;
    private final NotificationTemplateRepository templateRepository;
    private final PartyApi partyApi;
    private final ReferenceDataApi referenceDataApi;
    private final List<NotificationSender> senders;
    private final TransactionTemplate transactionTemplate;

    public OfferReminderDispatcher(NotificationDispatchRepository dispatchRepository,
                                    NotificationTemplateRepository templateRepository,
                                    PartyApi partyApi,
                                    ReferenceDataApi referenceDataApi,
                                    List<NotificationSender> senders,
                                    PlatformTransactionManager transactionManager) {
        this.dispatchRepository = dispatchRepository;
        this.templateRepository = templateRepository;
        this.partyApi = partyApi;
        this.referenceDataApi = referenceDataApi;
        this.senders = senders;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Every five minutes by default. Frequent enough that a reminder queued at 03:00 goes out
     * promptly, and cheap enough to be nothing on an idle database: the query is an indexed read
     * returning no rows for all but a few minutes a day.
     */
    @Scheduled(fixedDelayString = "${communication.reminder-drain-interval-ms:300000}")
    public void drainPendingReminders() {
        // Ids and tenants only -- see findPendingAcrossTenants. The tenant is set from the row
        // before anything else is read, so every subsequent query runs under normal RLS.
        for (Object[] queued : dispatchRepository.findPendingAcrossTenants()) {
            UUID dispatchId = (UUID) queued[0];
            UUID tenantId = (UUID) queued[1];
            try {
                dispatch(dispatchId, tenantId);
            } catch (Exception e) {
                // One bad row must not stop the queue. A customer whose party record has since
                // been deleted should not cost every other customer their reminder.
                log.error("Failed to dispatch reminder {}", dispatchId, e);
            }
        }
    }

    /**
     * Claim and send one queued reminder.
     *
     * <p>Public because it is a genuine single-row entry point rather than an internal step: the
     * scheduled drain is a loop over it, a test drives one row without waiting on the clock, and
     * an operator-triggered resend would call exactly this. Safe to call on any id — an
     * already-claimed row returns without sending.
     */
    public void dispatch(UUID dispatchId, UUID tenantId) {
        // The tenant is set FIRST and around everything, including the claim. Every query below
        // runs under RLS against exactly this tenant's rows -- so a dispatch id from one tenant
        // cannot be used to claim or read another's, even though the id arrived from a
        // cross-tenant lookup.
        UUID previousTenant = TenantContext.getOrNull();
        TenantContext.set(tenantId);
        try {
            // The claim, in its own committed transaction. Two instances reading the same PENDING
            // row both reach here; exactly one updates a row and the other gets zero back.
            Integer claimed = transactionTemplate.execute(status -> dispatchRepository.claimForSending(dispatchId));
            if (claimed == null || claimed == 0) {
                return;
            }
            transactionTemplate.executeWithoutResult(status -> {
                NotificationDispatch dispatch = dispatchRepository.findById(dispatchId).orElseThrow();
                sendOne(dispatch);
                dispatchRepository.save(dispatch);
            });
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }

    private void sendOne(NotificationDispatch dispatch) {
        NotificationChannel channel = NotificationChannel.valueOf(dispatch.getChannel());

        // Tenant's own wording, else the platform default -- the same fallback the event-driven
        // path uses. Without it a tenant that has customised nothing gets reminders that fail
        // while its offer and cover messages work, which would be a baffling thing to debug.
        Optional<NotificationTemplate> template = templateRepository
            .findByTenantIdAndTemplateKeyAndChannelAndLanguage(
                dispatch.getTenantId(), TEMPLATE_KEY, dispatch.getChannel(), DEFAULT_LANGUAGE)
            .or(() -> templateRepository.findByTenantIdAndTemplateKeyAndChannelAndLanguage(
                PLATFORM_DEFAULT_TENANT, TEMPLATE_KEY, dispatch.getChannel(), DEFAULT_LANGUAGE));
        if (template.isEmpty()) {
            dispatch.markFailed("No " + channel + "/" + DEFAULT_LANGUAGE + " template seeded for " + TEMPLATE_KEY);
            return;
        }

        PartyDetailView party = partyApi.getPartyDetail(dispatch.getPartyId());
        String destination = channel == NotificationChannel.SMS ? party.phoneNumber() : party.email();
        if (destination == null || destination.isBlank()) {
            // On file when the sweep queued this, gone now. Recorded rather than dropped, like
            // every other unreachable case.
            dispatch.markFailed("No " + channel + " address on file for this party any more");
            return;
        }

        String body;
        try {
            body = TemplateRenderer.render(template.get().getBodyTemplate(), Map.of(
                "policyNumber", dispatch.getPolicyNumber(),
                "expiryDate", expiryDateFor(dispatch)));
        } catch (IllegalArgumentException e) {
            dispatch.markFailed(e.getMessage());
            return;
        }

        NotificationSender.SendResult result = senders.stream()
            .filter(sender -> sender.supports(channel)).findFirst()
            .map(sender -> sender.send(destination, body))
            .orElseGet(() -> NotificationSender.SendResult.failed("No sender configured for " + channel));

        if (result.sent()) {
            dispatch.markSent();
        } else {
            dispatch.markFailed(result.detail());
        }
    }

    /**
     * The date this offer closes.
     *
     * <p>Derived from when the reminder was queued plus the reminder window, not read off the
     * policy: {@code communication} may not touch {@code policy.policy}. The sweep queues a row
     * only when the offer closes within exactly that window, so the arithmetic is right for every
     * row it produces — and a row produced any other way is a bug in whatever produced it.
     */
    private String expiryDateFor(NotificationDispatch dispatch) {
        long reminderDays;
        try {
            reminderDays = Long.parseLong(referenceDataApi.getValue("TZ_OFFER_REMINDER_DAYS", "TZ"));
        } catch (RuntimeException e) {
            log.warn("TZ_OFFER_REMINDER_DAYS unreadable; using {} for the reminder window",
                DEFAULT_REMINDER_DAYS, e);
            reminderDays = DEFAULT_REMINDER_DAYS;
        }
        return dispatch.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate().plusDays(reminderDays).toString();
    }
}
