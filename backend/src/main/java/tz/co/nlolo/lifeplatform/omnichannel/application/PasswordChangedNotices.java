package tz.co.nlolo.lifeplatform.omnichannel.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tz.co.nlolo.lifeplatform.communication.api.NotificationApi;
import tz.co.nlolo.lifeplatform.iam.api.StaffContacts;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * "Your password was changed -- if this wasn't you, call us" (2026-11-06, universal login step 2). Keycloak tells the
 * platform after every password change; the platform holds the phone numbers, so it sends: the client record's phone
 * for agents and customers, the staff record's for staff. A text the person did not expect is how they learn someone
 * else has their account.
 *
 * <p>Never throws, and says nothing back: a missing phone or a failed gateway is logged and that is all.
 */
@Service
public class PasswordChangedNotices {

    private static final Logger log = LoggerFactory.getLogger(PasswordChangedNotices.class);
    static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");
    static final String TEMPLATE = "PASSWORD_CHANGED";

    private final PartyApi parties;
    private final StaffContacts staff;
    private final NotificationApi notifications;
    private final String supportPhone;
    private final Clock clock;

    @Autowired
    public PasswordChangedNotices(PartyApi parties, StaffContacts staff, NotificationApi notifications,
                                  @Value("${app.support-phone:}") String supportPhone) {
        this(parties, staff, notifications, supportPhone, Clock.systemUTC());
    }

    PasswordChangedNotices(PartyApi parties, StaffContacts staff, NotificationApi notifications, String supportPhone,
                           Clock clock) {
        this.parties = parties;
        this.staff = staff;
        this.notifications = notifications;
        this.supportPhone = supportPhone == null ? "" : supportPhone.trim();
        this.clock = clock;
    }

    /** For a party (agent, customer) when {@code partyId} is given, else for the staff member {@code userId}. */
    public void passwordChanged(UUID userId, UUID partyId, String language) {
        boolean swahili = "sw".equalsIgnoreCase(language);
        String body = text(ZonedDateTime.now(clock.withZone(ZONE)), supportPhone, swahili);
        try {
            if (partyId != null) {
                String phone = parties.getPartyDetail(partyId).phoneNumber();
                if (phone == null || phone.isBlank()) {
                    log.info("Password-changed notice for party {} not texted: no phone number on file", partyId);
                    return;
                }
                String failure = notifications.sendUnloggedSms(partyId, phone.trim(), TEMPLATE, body, body);
                if (failure != null) log.warn("Password-changed notice for party {} not texted: {}", partyId, failure);
                return;
            }
            String phone = staff.phoneOf(userId).orElse(null);
            if (phone == null) {
                log.info("Password-changed notice for staff user {} not texted: no phone on the staff record", userId);
                return;
            }
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
