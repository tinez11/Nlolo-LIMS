package tz.co.nlolo.lifeplatform.omnichannel.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tz.co.nlolo.lifeplatform.communication.api.NotificationApi;
import tz.co.nlolo.lifeplatform.iam.api.StaffContacts;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A staff member's sign-in code, texted (2026-11-14, spec 2026-11-14-staff-sign-in-code-design.md). Authentik makes the
 * code and asks for it after the password (2026-11-19, the Authentik cut-over); the platform holds the phone and
 * language on the staff record, the wording -- the editable STAFF_SIGN_IN_CODE template -- and the caps. The code is
 * never logged and never kept: {@code sendSystemSms} records no message-log row.
 *
 * <p>Whether to ask for a code at all is Authentik's flow, under the STAFF_SIGN_IN_CODE switch; production refuses to
 * start with it off (ProductionSettingsGuard).
 *
 * <p>The caps -- {@link #MAX_PER_HOUR} an hour and one a minute, per person -- are held in memory, as the public quote's
 * are (one backend in the pilot): a restart only forgets recent sends. Keycloak ended the attempt after three wrong
 * codes; Authentik instead slows each retry, and these caps stop a stream of texts.
 */
@Service
public class StaffSignInCodes {

    /** What Authentik is told: texted; no phone to text; too many codes lately; or the gateway did not take it. */
    public enum Result { SENT, NO_PHONE, TOO_MANY, NOT_SENT }

    static final String TEMPLATE = "STAFF_SIGN_IN_CODE";
    static final int MAX_PER_HOUR = 5;
    static final Duration MIN_GAP = Duration.ofMinutes(1);
    private static final Logger log = LoggerFactory.getLogger(StaffSignInCodes.class);

    private final StaffContacts staff;
    private final NotificationApi notifications;
    private final Clock clock;
    private final Map<UUID, Deque<Instant>> sends = new ConcurrentHashMap<>();

    @Autowired
    public StaffSignInCodes(StaffContacts staff, NotificationApi notifications) {
        this(staff, notifications, Clock.systemUTC());
    }

    StaffSignInCodes(StaffContacts staff, NotificationApi notifications, Clock clock) {
        this.staff = staff;
        this.notifications = notifications;
        this.clock = clock;
    }

    /**
     * In the tenant already set from the request: the staff member's own. {@code loginPhone} is the {@code phone} on
     * the Authentik login, set when it was made on the server (deploy/server/add-user.sh --phone): the first
     * administrator has no staff record until their first sign-in, and without it nobody could sign in to give anyone a
     * phone. The staff record's phone wins whenever there is one; a login phone that is not a phone number is ignored.
     */
    public Result send(UUID userId, String code, String loginPhone) {
        String phone = staff.phoneOf(userId).orElse(asPhone(loginPhone));
        if (phone == null) {
            log.info("Sign-in code for staff user {} not texted: no phone on the staff record or the login", userId);
            return Result.NO_PHONE;
        }
        if (!withinCaps(userId, true)) {
            log.info("Sign-in code for staff user {} not texted: over the cap", userId);
            return Result.TOO_MANY;
        }
        // The wording in force, in their language -- unless an edit dropped the code, when the built-in text goes:
        // a sign-in code message without the code locks everyone out.
        String body = notifications.renderSystemTemplate(TEMPLATE, staff.languageOf(userId), Map.of("code", code))
            .filter(text -> text.contains(code))
            .orElseGet(() -> {
                log.warn("The {} wording in force does not carry the code; the built-in text was sent", TEMPLATE);
                return builtIn(code);
            });
        String failure = notifications.sendSystemSms(phone, TEMPLATE, body);
        if (failure != null) {
            log.warn("Sign-in code for staff user {} not texted: {}", userId, failure);
            return Result.NOT_SENT;
        }
        return Result.SENT;
    }

    /**
     * Why a code could not be sent now -- no phone, or over a cap -- or empty when it could. Asked before the code step
     * (2026-11-19), because Authentik's code pages do not show a refused send; asking never counts as a send.
     */
    public Optional<Result> refusal(UUID userId, String loginPhone) {
        if (staff.phoneOf(userId).orElse(asPhone(loginPhone)) == null) return Optional.of(Result.NO_PHONE);
        if (!withinCaps(userId, false)) return Optional.of(Result.TOO_MANY);
        return Optional.empty();
    }

    static String builtIn(String code) {
        return "Your Nlolo sign-in code is " + code + ". It expires in 5 minutes. Never share it.";
    }

    private static String asPhone(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.matches("\\+?\\d{9,15}") ? trimmed : null;
    }

    /**
     * One a minute and {@link #MAX_PER_HOUR} an hour. With {@code count}, a send let through is counted, whatever the
     * gateway then says; without, it is only a question.
     */
    private boolean withinCaps(UUID userId, boolean count) {
        Instant now = clock.instant();
        Deque<Instant> recent = sends.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (recent) {
            while (!recent.isEmpty() && !recent.peekFirst().isAfter(now.minus(Duration.ofHours(1)))) recent.pollFirst();
            if (recent.size() >= MAX_PER_HOUR) return false;
            if (!recent.isEmpty() && recent.peekLast().isAfter(now.minus(MIN_GAP))) return false;
            if (count) recent.addLast(now);
            return true;
        }
    }
}
