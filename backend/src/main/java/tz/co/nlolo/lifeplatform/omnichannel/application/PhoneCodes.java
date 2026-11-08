package tz.co.nlolo.lifeplatform.omnichannel.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tz.co.nlolo.lifeplatform.communication.api.NotificationApi;
import tz.co.nlolo.lifeplatform.party.api.PhoneNumbers;

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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Proves a visitor holds the phone they gave, before an application opens in their name (2026-11-02, the user): a
 * six-digit code by SMS, valid {@link #VALID} and used once, {@link #MAX_TRIES} tries, and at most
 * {@link #MAX_SENDS_PER_HOUR} texts an hour to one phone.
 *
 * <p>Held in memory, as the public link's other guards are (one backend in the pilot, deployment D1): a restart only
 * means asking for a new code. Only a hash of the code is kept, tied to the visit that asked for it, so a code read off
 * somebody else's phone is no use in another visit. Nothing is written to the message log -- the visitor has no
 * client record yet.
 */
@Service
public class PhoneCodes {

    private static final Logger log = LoggerFactory.getLogger(PhoneCodes.class);
    static final Duration VALID = Duration.ofMinutes(10);
    static final int MAX_TRIES = 5;
    static final int MAX_SENDS_PER_HOUR = 3;

    private final NotificationApi notifications;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> sends = new ConcurrentHashMap<>();

    @Autowired
    public PhoneCodes(NotificationApi notifications) {
        this(notifications, Clock.systemUTC());
    }

    PhoneCodes(NotificationApi notifications, Clock clock) {
        this.notifications = notifications;
        this.clock = clock;
    }

    private record Pending(String visit, String hash, Instant expires, int triesLeft) {}

    /** Why a code was not sent or not accepted, in words the visitor reads. */
    public static class Refused extends RuntimeException {
        public Refused(String message) {
            super(message);
        }
    }

    /** The phone as the platform stores it (+255...), or a refusal when it is not one. */
    public static String phone(String typed) {
        String phone = PhoneNumbers.normalise(typed);
        if (phone == null || !phone.matches("\\+\\d{11,13}")) {
            throw new Refused("Enter a mobile number, such as 0712 345 678");
        }
        return phone;
    }

    /** Texts a fresh code to {@code phone} for this visit; any earlier code for that phone stops working. */
    public void send(String phone, String visit, boolean swahili) {
        if (!withinCap(phone)) {
            throw new Refused(swahili
                ? "Tumetuma namba mara tatu ndani ya saa moja. Subiri kidogo kisha ujaribu tena."
                : "We have sent three codes to this phone in the last hour. Please wait a while and try again.");
        }
        String code = String.format("%06d", random.nextInt(1_000_000));
        pending.put(phone, new Pending(visit, hash(phone, code), clock.instant().plus(VALID), MAX_TRIES));
        String failure = notifications.sendSystemSms(phone, "PUBLIC_APPLY_CODE", text(code, swahili));
        if (failure != null) {
            pending.remove(phone);
            log.warn("Public application code not texted: {}", failure);
            throw new Refused(swahili
                ? "Hatukuweza kutuma ujumbe kwa namba hii sasa hivi. Hakikisha namba ni sahihi, kisha jaribu tena."
                : "We could not text this number just now. Check it is right, then try again.");
        }
    }

    /**
     * Whether {@code code} is the one texted to {@code phone} in this visit. Used once: a right code is spent; a wrong
     * one costs a try, and the last try spends the code.
     */
    public void check(String phone, String visit, String code, boolean swahili) {
        Pending p = pending.get(phone);
        Instant now = clock.instant();
        if (p == null || !p.visit().equals(visit) || now.isAfter(p.expires())) {
            pending.remove(phone, p);
            throw new Refused(swahili ? "Namba hii imekwisha muda wake. Omba namba mpya."
                : "That code has expired. Ask for a new one.");
        }
        boolean right = code != null && MessageDigest.isEqual(
            hash(phone, code.trim()).getBytes(StandardCharsets.US_ASCII), p.hash().getBytes(StandardCharsets.US_ASCII));
        if (right) {
            pending.remove(phone, p);
            return;
        }
        int left = p.triesLeft() - 1;
        if (left <= 0) {
            pending.remove(phone, p);
            throw new Refused(swahili ? "Namba si sahihi, na majaribio yamekwisha. Omba namba mpya."
                : "That code is not right, and there are no tries left. Ask for a new one.");
        }
        pending.replace(phone, p, new Pending(p.visit(), p.hash(), p.expires(), left));
        throw new Refused(swahili ? "Namba si sahihi. Umebakiza majaribio " + left + "."
            : "That code is not right. " + left + (left == 1 ? " try" : " tries") + " left.");
    }

    /** The code first, how long it lasts, and the one rule that stops it being phished. */
    static String text(String code, boolean swahili) {
        return swahili
            ? "Namba yako ya kuthibitisha ombi ni " + code + ". Inatumika kwa dakika 10. Usimpe mtu yeyote."
            : "Your application code is " + code + ". It works for 10 minutes. Do not share it with anyone.";
    }

    private boolean withinCap(String phone) {
        Instant now = clock.instant();
        Deque<Instant> recent = sends.computeIfAbsent(phone, k -> new ArrayDeque<>());
        synchronized (recent) {
            while (!recent.isEmpty() && recent.peekFirst().isBefore(now.minus(Duration.ofHours(1)))) recent.pollFirst();
            if (recent.size() >= MAX_SENDS_PER_HOUR) return false;
            recent.addLast(now);
            return true;
        }
    }

    private static String hash(String phone, String code) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest((phone + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
