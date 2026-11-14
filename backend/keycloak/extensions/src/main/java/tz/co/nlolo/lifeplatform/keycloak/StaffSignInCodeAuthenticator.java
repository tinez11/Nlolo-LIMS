package tz.co.nlolo.lifeplatform.keycloak;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.common.util.Time;
import org.keycloak.events.Errors;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.SingleUseObjectProvider;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The staff sign-in code (2026-11-14, spec 2026-11-14-staff-sign-in-code-design.md): after the password, a staff
 * member enters a six-digit code texted to the phone on their staff record. Agents, customers and regulators pass
 * straight through. The platform decides whether codes are on at all: when it answers OFF, the person is let in on
 * their password, as before.
 *
 * <p>Only the code's hash sits in the session, it lives five minutes, it is spent once used, and three wrong codes end
 * the attempt. A wrong code is a failed sign-in to Keycloak's brute-force lockout, as a wrong password is.
 */
final class StaffSignInCodeAuthenticator implements Authenticator {

    static final String TEMPLATE = "lp-sign-in-code.ftl";
    private static final String NOTE_SALT = "lp.signin.salt";
    private static final String NOTE_HASH = "lp.signin.hash";
    private static final String NOTE_EXPIRES = "lp.signin.expires";
    private static final String NOTE_ATTEMPTS = "lp.signin.attempts";
    private static final String NOTE_LAST_SENT = "lp.signin.lastSent";

    private final StaffCodeTexter texter;

    StaffSignInCodeAuthenticator(StaffCodeTexter texter) {
        this.texter = texter;
    }

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        UserModel user = context.getUser();
        RoleModel staff = context.getRealm().getRole(ResetDeliveryAuthenticator.STAFF_ROLE);
        if (user == null || staff == null || !user.hasRole(staff)) {
            context.success();
            return;
        }
        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        // A reload or a change of language comes back here: while a code is live, the page is only drawn again --
        // texting another would void the one the person is holding.
        boolean live = authSession.getAuthNote(NOTE_HASH) != null && Time.currentTime() <= longNote(authSession, NOTE_EXPIRES);
        if (live) {
            context.challenge(page(context, null, null));
            return;
        }
        deliver(context, null);
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        MultivaluedMap<String, String> form = context.getHttpRequest().getDecodedFormParameters();
        AuthenticationSessionModel authSession = context.getAuthenticationSession();

        if (form.containsKey("resend")) {
            if (Time.currentTime() - longNote(authSession, NOTE_LAST_SENT) < SignInCodes.RESEND_GAP_SECONDS) {
                context.challenge(page(context, "lpSignInCodeWaitMinute", null));
                return;
            }
            deliver(context, "lpSignInCodeResent");
            return;
        }
        if (authSession.getAuthNote(NOTE_HASH) == null || Time.currentTime() > longNote(authSession, NOTE_EXPIRES)) {
            context.getEvent().user(context.getUser()).error(Errors.EXPIRED_CODE);
            context.challenge(page(context, "lpSignInCodeExpired", null));
            return;
        }
        boolean right = ResetCodes.matches(authSession.getAuthNote(NOTE_SALT), authSession.getAuthNote(NOTE_HASH),
            form.getFirst("code"));
        if (!right) {
            int attempts = (int) longNote(authSession, NOTE_ATTEMPTS) + 1;
            authSession.setAuthNote(NOTE_ATTEMPTS, Integer.toString(attempts));
            context.getEvent().user(context.getUser()).error(Errors.INVALID_CODE);
            if (attempts >= SignInCodes.MAX_ATTEMPTS) {
                forget(authSession);
                // The attempt is over: the page offers only "sign in again".
                context.failure(AuthenticationFlowError.INVALID_CREDENTIALS,
                    page(context, "lpSignInTooManyAttempts", null, true));
                return;
            }
            // failureChallenge, not challenge: a wrong code counts towards the realm's lockout, as a wrong password does.
            context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS, page(context, "lpSignInWrongCode", null));
            return;
        }
        forget(authSession);
        context.success();
    }

    /** Makes a code, keeps its hash, and asks the platform to text it; then shows the page or lets the person through. */
    private void deliver(AuthenticationFlowContext context, String infoWhenSent) {
        UserModel user = context.getUser();
        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        if (!withinHourlyCap(context.getSession(), context.getRealm(), user)) {
            context.getEvent().clone().user(user).error("too_many_sign_in_codes");
            context.challenge(page(context, "lpSignInCodeWait", null));
            return;
        }
        String code = ResetCodes.newCode();
        String salt = ResetCodes.newSalt();
        authSession.setAuthNote(NOTE_SALT, salt);
        authSession.setAuthNote(NOTE_HASH, ResetCodes.hash(salt, code));
        authSession.setAuthNote(NOTE_EXPIRES, Long.toString(Time.currentTime() + SignInCodes.LIFETIME_SECONDS));
        authSession.setAuthNote(NOTE_ATTEMPTS, "0");
        authSession.setAuthNote(NOTE_LAST_SENT, Long.toString(Time.currentTime()));

        String answer = texter.send(user.getFirstAttribute("tenant_id"), user.getId(), code);
        switch (answer) {
            case "OFF" -> {
                // Codes are switched off on the platform (dev, tests): in on the password, as before.
                forget(authSession);
                context.success();
            }
            case "NO_PHONE" -> {
                forget(authSession);
                context.getEvent().clone().user(user).error("staff_sign_in_no_phone");
                context.failure(AuthenticationFlowError.ACCESS_DENIED, page(context, "lpSignInNoPhone", null, true));
            }
            case "SENT" -> context.challenge(page(context, null, infoWhenSent));
            default -> {
                context.getEvent().clone().user(user).error("staff_sign_in_code_not_sent");
                context.challenge(page(context, "lpSignInCodeNotSent", null));
            }
        }
    }

    /** At most five texts an hour, and one a minute, to one account across browsers -- as the reset code counts them. */
    private static boolean withinHourlyCap(KeycloakSession session, RealmModel realm, UserModel user) {
        SingleUseObjectProvider store = session.singleUseObjects();
        String key = "lp-signin-sends:" + realm.getId() + ":" + user.getId();
        long now = Time.currentTime();
        Map<String, String> notes = store.get(key);
        List<Long> sends = new ArrayList<>(ResetCodes.recentSends(notes == null ? null : notes.get("at"), now));
        if (!SignInCodes.maySend(sends, now)) return false;
        sends.add(now);
        Map<String, String> next = new HashMap<>();
        next.put("at", ResetCodes.store(sends));
        if (notes != null) store.remove(key);
        store.put(key, 3600, next);
        return true;
    }

    private static void forget(AuthenticationSessionModel authSession) {
        authSession.removeAuthNote(NOTE_HASH);
        authSession.removeAuthNote(NOTE_SALT);
        authSession.removeAuthNote(NOTE_EXPIRES);
        authSession.removeAuthNote(NOTE_ATTEMPTS);
    }

    private Response page(AuthenticationFlowContext context, String error, String info) {
        return page(context, error, info, false);
    }

    /** {@code ended}: the attempt is over, so the page has no code field -- only the way back to sign in. */
    private Response page(AuthenticationFlowContext context, String error, String info, boolean ended) {
        var form = context.form().setAttribute("lpSignInCodeDigits", ResetCodes.DIGITS)
            .setAttribute("lpSignInMinutes", SignInCodes.LIFETIME_SECONDS / 60)
            .setAttribute("lpSignInEnded", ended);
        if (error != null) form.setError(error);
        if (info != null) form.setInfo(info);
        return form.createForm(TEMPLATE);
    }

    private static long longNote(AuthenticationSessionModel authSession, String note) {
        try {
            String value = authSession.getAuthNote(note);
            return value == null ? 0 : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public boolean requiresUser() {
        return true;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {}

    @Override
    public void close() {}
}
