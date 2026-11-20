package tz.co.nlolo.lifeplatform.omnichannel.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.communication.api.NotificationApi;
import tz.co.nlolo.lifeplatform.iam.api.StaffContacts;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyDetailView;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * "Your password was changed -- if this wasn't you, call us" (2026-11-06, universal login step 2). Authentik tells the
 * platform after a person's own change or reset (2026-11-19, the Authentik cut-over: a notification rule on its
 * {@code password_set} event); the platform holds the phone numbers, so it sends: the client record's phone for agents
 * and customers, the staff record's for staff, in the language on that record. A text the person did not expect is
 * how they learn someone else has their account.
 *
 * <p>Authentik retries a notice up to five times, so each event texts once. The text goes on a background thread --
 * Authentik is answered at once -- and is stamped with when Authentik saw the change, not when the text left. Never
 * throws: a missing phone or a failed gateway is logged and that is all.
 */
@Service
public class PasswordChangedNotices {

    private static final Logger log = LoggerFactory.getLogger(PasswordChangedNotices.class);
    static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");
    static final String TEMPLATE = "PASSWORD_CHANGED";
    /** Longer than Authentik's retries last (about 16 minutes), with room to spare. */
    private static final Duration REMEMBER = Duration.ofDays(1);

    private final PartyApi parties;
    private final StaffContacts staff;
    private final NotificationApi notifications;
    private final String supportPhone;
    private final Executor background;
    private final Map<String, Instant> seen = new ConcurrentHashMap<>();

    @Autowired
    public PasswordChangedNotices(PartyApi parties, StaffContacts staff, NotificationApi notifications,
                                  @Value("${app.support-phone:}") String supportPhone) {
        this(parties, staff, notifications, supportPhone, Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "password-changed-notices");
            thread.setDaemon(true);
            return thread;
        }));
    }

    PasswordChangedNotices(PartyApi parties, StaffContacts staff, NotificationApi notifications, String supportPhone,
                           Executor background) {
        this.parties = parties;
        this.staff = staff;
        this.notifications = notifications;
        this.supportPhone = supportPhone == null ? "" : supportPhone.trim();
        this.background = background;
    }

    /**
     * For a party (agent, customer) when {@code partyId} is given, else for the staff member {@code userId}; in the
     * tenant already set from the request. Returns false, and texts nothing, for an event already handled.
     */
    public boolean passwordChanged(String eventId, UUID userId, UUID partyId, Instant at) {
        Instant now = Instant.now();
        seen.values().removeIf(when -> when.isBefore(now.minus(REMEMBER)));
        if (seen.putIfAbsent(eventId, now) != null) {
            log.info("Password-changed event {} already handled; not texted again", eventId);
            return false;
        }
        UUID tenant = TenantContext.get();
        background.execute(() -> {
            TenantContext.set(tenant);
            try {
                send(userId, partyId, at);
            } finally {
                TenantContext.clear();
            }
        });
        return true;
    }

    private void send(UUID userId, UUID partyId, Instant at) {
        ZonedDateTime when = at.atZone(ZONE);
        try {
            if (partyId != null) {
                PartyDetailView party = parties.getPartyDetail(partyId);
                String phone = party.phoneNumber();
                if (phone == null || phone.isBlank()) {
                    log.info("Password-changed notice for party {} not texted: no phone number on file", partyId);
                    return;
                }
                String body = text(when, supportPhone, "sw".equalsIgnoreCase(party.preferredLanguage()));
                String failure = notifications.sendUnloggedSms(partyId, phone.trim(), TEMPLATE, body, body);
                if (failure != null) log.warn("Password-changed notice for party {} not texted: {}", partyId, failure);
                return;
            }
            String phone = staff.phoneOf(userId).orElse(null);
            if (phone == null) {
                log.info("Password-changed notice for staff user {} not texted: no phone on the staff record", userId);
                return;
            }
            String body = text(when, supportPhone, "sw".equalsIgnoreCase(staff.languageOf(userId)));
            String failure = notifications.sendSystemSms(phone, TEMPLATE, body);
            if (failure != null) log.warn("Password-changed notice for staff user {} not texted: {}", userId, failure);
        } catch (RuntimeException e) {
            log.warn("Password-changed notice for user {} not texted: {}", userId, e.toString());
        }
    }

    /** When, and who to call. Without a support number the second sentence says to contact the insurer. */
    static String text(ZonedDateTime at, String supportPhone, boolean swahili) {
        String day = at.format(DateTimeFormatter.ofPattern("d MMM", swahili ? Locale.forLanguageTag("sw") : Locale.ENGLISH));
        String time = at.format(DateTimeFormatter.ofPattern("HH:mm"));
        boolean hasPhone = supportPhone != null && !supportPhone.isBlank();
        if (swahili) {
            return "Nenosiri lako la Nlolo limebadilishwa tarehe " + day + " saa " + time + ". Kama si wewe, "
                + (hasPhone ? "piga " + supportPhone + "." : "wasiliana na kampuni mara moja.");
        }
        return "Your Nlolo password was changed on " + day + " at " + time + ". If this wasn't you, "
            + (hasPhone ? "call " + supportPhone + "." : "contact us straight away.");
    }
}
