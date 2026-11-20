package tz.co.nlolo.lifeplatform.omnichannel.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Forgot your password?" for agents and customers (2026-11-19, Authentik cut-over ticket 07): the platform's public
 * /reset page. The person types their phone number; a six-digit code is texted to the phone on their client record;
 * they type it on the same page, and the browser is sent to Authentik's one-time recovery link to choose a new
 * password. The link is never texted.
 *
 * <p>Keycloak's ResetCodes rules, moved here: the code lasts {@link #VALID}, {@link #MAX_TRIES} wrong tries spend it,
 * and a number gets at most {@link #MAX_PER_HOUR} codes an hour, one a minute. Nothing tells a number with a login from
 * one without: the page answers the same, the caps are the same, and an unknown number gets a code too -- one nobody is
 * ever sent -- so a guess answers exactly as it would for a real number. Staff are never reached (ResetLogins offers no
 * staff login): they can't reset their own password.
 *
 * <p>Held in memory, as the public quote's codes are (one backend in the pilot): a restart only means asking again.
 * Only a hash of each code is kept.
 */
@Service
public class PasswordResets {

    static final Duration VALID = Duration.ofMinutes(15);
    static final int MAX_TRIES = 5;
    static final int MAX_PER_HOUR = 3;
    static final Duration MIN_GAP = Duration.ofMinutes(1);

    /** Texts a reset code to a party's phone, in their language; says nothing back. */
    @FunctionalInterface
    public interface Texter {
        void send(UUID partyId, String code, String language);
    }

    /** Why a code was not sent or not accepted, in words the person reads. */
    public static class Refused extends RuntimeException {
        private final boolean tooMany;

        Refused(String message, boolean tooMany) {
            super(message);
            this.tooMany = tooMany;
        }

        public boolean tooMany() {
            return tooMany;
        }
    }

    private record Pending(String hash, Instant expires, int triesLeft, UUID userId) {}

    private final ResetLogins logins;
    private final Texter texter;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> sends = new ConcurrentHashMap<>();

    @Autowired
    public PasswordResets(ResetLogins logins, PasswordResetTexts texts) {
        this(logins, texts::send, Clock.systemUTC());
    }

    PasswordResets(ResetLogins logins, Texter texter, Clock clock) {
        this.logins = logins;
        this.texter = texter;
        this.clock = clock;
    }

    /** Texts a fresh code when {@code typed} is an agent's or customer's login; answers the same either way. */
    public void request(String typed, boolean swahili) {
        String phone = phone(typed, swahili);
        withinCaps(phone, swahili);
        String code = String.format("%06d", random.nextInt(1_000_000));
        Optional<ResetLogins.ResetLogin> login = logins.find(phone);
        pending.put(phone, new Pending(hash(phone, code), clock.instant().plus(VALID), MAX_TRIES,
            login.map(ResetLogins.ResetLogin::userId).orElse(null)));
        login.ifPresent(l -> texter.send(l.partyId(), code, swahili ? "sw" : "en"));
    }

    /** The recovery link when {@code code} is the one texted for {@code typed}; a refusal in their words otherwise. */
    public String check(String typed, String code, boolean swahili) {
        String phone = phone(typed, swahili);
        Pending p = pending.get(phone);
        Instant now = clock.instant();
        if (p == null || now.isAfter(p.expires())) {
            pending.remove(phone, p);
            throw refused(swahili, "Namba hii imekwisha muda wake. Omba namba mpya.",
                "That code has expired. Ask for a new code.");
        }
        String typedCode = code == null ? "" : code.replaceAll("[\\s-]", "");
        boolean right = MessageDigest.isEqual(hash(phone, typedCode).getBytes(StandardCharsets.US_ASCII),
            p.hash().getBytes(StandardCharsets.US_ASCII));
        if (right && p.userId() != null) {
            pending.remove(phone, p);
            return logins.recoveryLink(p.userId());
        }
        // A wrong code -- or any code for a number with no login, which no one was sent -- costs a try.
        int left = p.triesLeft() - 1;
        if (left <= 0) {
            pending.remove(phone, p);
            throw refused(swahili, "Namba si sahihi, na majaribio yamekwisha. Omba namba mpya.",
                "That code is not right, and there are no tries left. Ask for a new code.");
        }
        pending.replace(phone, p, new Pending(p.hash(), p.expires(), left, p.userId()));
        throw refused(swahili, "Namba si sahihi. Umebakiza majaribio " + left + ".",
            "That code is not right. " + left + (left == 1 ? " try" : " tries") + " left.");
    }

    private static String phone(String typed, boolean swahili) {
        try {
            return PhoneCodes.phone(typed);
        } catch (RuntimeException notAPhone) {
            throw refused(swahili, "Andika namba ya simu, kwa mfano 0712 345 678",
                "Enter a mobile number, such as 0712 345 678");
        }
    }

    private void withinCaps(String phone, boolean swahili) {
        Instant now = clock.instant();
        Deque<Instant> recent = sends.computeIfAbsent(phone, k -> new ArrayDeque<>());
        synchronized (recent) {
            while (!recent.isEmpty() && !recent.peekFirst().isAfter(now.minus(Duration.ofHours(1)))) recent.pollFirst();
            if (recent.size() >= MAX_PER_HOUR) {
                throw new Refused(swahili
                    ? "Umeomba namba nyingi mno kwa namba hii. Jaribu tena baada ya saa moja."
                    : "Too many codes for this number. Try again in an hour.", true);
            }
            if (!recent.isEmpty() && recent.peekLast().isAfter(now.minus(MIN_GAP))) {
                throw new Refused(swahili
                    ? "Subiri dakika moja kabla ya kuomba namba mpya."
                    : "Wait a minute before asking for a new code.", true);
            }
            recent.addLast(now);
        }
    }

    private static Refused refused(boolean swahili, String sw, String en) {
        return new Refused(swahili ? sw : en, false);
    }

    private static String hash(String phone, String code) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(("reset:" + phone + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
