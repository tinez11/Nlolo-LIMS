package tz.co.nlolo.lifeplatform.omnichannel.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tz.co.nlolo.lifeplatform.communication.api.NotificationApi;
import tz.co.nlolo.lifeplatform.iam.api.StaffContacts;

import java.util.Map;
import java.util.UUID;

/**
 * A staff member's sign-in code, texted (2026-11-14, spec 2026-11-14-staff-sign-in-code-design.md). Keycloak makes the
 * code and asks after the password; the platform holds the switch, the phone and language on the staff record, and the
 * wording -- the editable STAFF_SIGN_IN_CODE template. The code is never logged and never kept: {@code sendSystemSms}
 * records no message-log row.
 *
 * <p>With the switch off the answer is OFF and Keycloak lets the person in on their password, as before: dev and the
 * test suites sign in unchanged. Production refuses to start with it off (ProductionSettingsGuard).
 */
@Service
public class StaffSignInCodes {

    /** What Keycloak is told: texted; switched off; no phone to text; or the gateway did not take it. */
    public enum Result { SENT, OFF, NO_PHONE, NOT_SENT }

    static final String TEMPLATE = "STAFF_SIGN_IN_CODE";
    private static final Logger log = LoggerFactory.getLogger(StaffSignInCodes.class);

    private final boolean enabled;
    private final StaffContacts staff;
    private final NotificationApi notifications;

    public StaffSignInCodes(@Value("${app.staff-sign-in-code.enabled:false}") boolean enabled, StaffContacts staff,
                            NotificationApi notifications) {
        this.enabled = enabled;
        this.staff = staff;
        this.notifications = notifications;
    }

    /** In the tenant already set from the request: the staff member's own. */
    public Result send(UUID userId, String code) {
        if (!enabled) {
            return Result.OFF;
        }
        String phone = staff.phoneOf(userId).orElse(null);
        if (phone == null) {
            log.info("Sign-in code for staff user {} not texted: no phone on the staff record", userId);
            return Result.NO_PHONE;
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

    static String builtIn(String code) {
        return "Your Nlolo sign-in code is " + code + ". It expires in 5 minutes. Never share it.";
    }
}
