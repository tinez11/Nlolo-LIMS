package tz.co.nlolo.lifeplatform.omnichannel.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tz.co.nlolo.lifeplatform.communication.api.NotificationApi;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyDetailView;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Texts a password-reset code to the phone the platform holds for a person (2026-10-28, user decision: reset by SMS
 * for agents and customers, who often have no email). Keycloak makes and checks the code; the phone number is the
 * platform's, so the platform sends. Through the unlogged path: the message log keeps the text with the code masked,
 * as it does for a portal one-time password.
 *
 * <p>At most three texts an hour to one person, here as well as in Keycloak, so the phone is protected even if the
 * other side is misconfigured. Says nothing back about whether a phone existed: Keycloak shows one page either way.
 */
@Service
public class PasswordResetTexts {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetTexts.class);
    static final int MAX_PER_HOUR = 3;
    static final String MASK = "******";

    private final PartyApi parties;
    private final NotificationApi notifications;
    private final Clock clock;
    private final Map<UUID, Deque<Instant>> recent = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public PasswordResetTexts(PartyApi parties, NotificationApi notifications) {
        this(parties, notifications, Clock.systemUTC());
    }

    PasswordResetTexts(PartyApi parties, NotificationApi notifications, Clock clock) {
        this.parties = parties;
        this.notifications = notifications;
        this.clock = clock;
    }

    /** Sends the code to the person's phone when they have one and have not had three this hour. Never throws. */
    public void send(UUID partyId, String code, String language) {
        if (code == null || !code.matches("\\d{6}")) {
            log.warn("Refused a reset code for party {}: not six digits", partyId);
            return;
        }
        if (!withinCap(partyId)) {
            log.warn("Reset code for party {} not texted: three already this hour", partyId);
            return;
        }
        PartyDetailView party;
        try {
            party = parties.getPartyDetail(partyId);
        } catch (RuntimeException notFound) {
            log.warn("Reset code for party {} not texted: no such party in this tenant", partyId);
            return;
        }
        String phone = party.phoneNumber();
        if (phone == null || phone.isBlank()) {
            log.info("Reset code for party {} not texted: no phone number on file", partyId);
            return;
        }
        boolean swahili = "sw".equalsIgnoreCase(language);
        String failure = notifications.sendUnloggedSms(partyId, phone.trim(), "PASSWORD_RESET_CODE",
            text(code, swahili), text(MASK, swahili));
        if (failure != null) {
            log.warn("Reset code for party {} not texted: {}", partyId, failure);
        }
    }

    /** The code first, the lifetime, and the one rule that stops it being phished. */
    static String text(String code, boolean swahili) {
        return swahili
            ? "Namba yako ya kubadilisha nenosiri ni " + code + ". Inatumika kwa dakika 15. Usimpe mtu yeyote:"
                + " hatutawahi kukuuliza namba hii."
            : "Your password reset code is " + code + ". It works for 15 minutes. Do not share it: we will never ask"
                + " you for it.";
    }

    private boolean withinCap(UUID partyId) {
        Instant now = clock.instant();
        Deque<Instant> sends = recent.computeIfAbsent(partyId, id -> new ArrayDeque<>());
        synchronized (sends) {
            while (!sends.isEmpty() && sends.peekFirst().isBefore(now.minusSeconds(3600))) sends.pollFirst();
            if (sends.size() >= MAX_PER_HOUR) return false;
            sends.addLast(now);
            return true;
        }
    }
}
