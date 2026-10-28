package tz.co.nlolo.lifeplatform.keycloak;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.DefaultActionTokenKey;
import org.keycloak.authentication.actiontoken.resetcred.ResetCredentialsActionToken;
import org.keycloak.common.util.Time;
import org.keycloak.email.EmailException;
import org.keycloak.email.EmailTemplateProvider;
import org.keycloak.events.Details;
import org.keycloak.events.Errors;
import org.keycloak.events.EventType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.SingleUseObjectProvider;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionCompoundId;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The reset step of the agents' and customers' "Forgot password?" (2026-10-28), in place of Keycloak's email-only one.
 *
 * <p>Once a person has named their account, this emails a reset link to the address on file -- exactly as Keycloak's
 * own step does -- and texts a six-digit code to the phone on file, through the platform. Whichever the account has.
 * Then it shows one page: enter the code, or use the link. The page is the same for every name typed, a real account or
 * not, with a phone or without: what it reveals about the account is nothing. A code that was never sent simply never
 * matches.
 *
 * <p>The email link brings the person back into this same flow, already proven; the code proves them here. Either way
 * the next step is choosing a new password, and every other session is signed out by default there.
 */
final class ResetDeliveryAuthenticator implements Authenticator {

    private static final Logger log = Logger.getLogger(ResetDeliveryAuthenticator.class);

    static final String TEMPLATE = "lp-reset-code.ftl";
    private static final String NOTE_SALT = "lp.reset.salt";
    private static final String NOTE_HASH = "lp.reset.hash";
    private static final String NOTE_EXPIRES = "lp.reset.expires";
    private static final String NOTE_ATTEMPTS = "lp.reset.attempts";
    private static final String NOTE_LAST_SENT = "lp.reset.lastSent";

    private final ResetCodeTexter texter;

    ResetDeliveryAuthenticator(ResetCodeTexter texter) {
        this.texter = texter;
    }

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        UserModel user = context.getUser();
        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        // Back from the emailed link: the action token has already proven the person. Keycloak's own step does this.
        String proven = authSession.getAuthNote(DefaultActionTokenKey.ACTION_TOKEN_USER_ID);
        if (user != null && proven != null && proven.equals(user.getId())) {
            context.success();
            return;
        }
        // A reload, or a change of language, comes back here too. Sending again would text a second code and void the
        // first one the person is holding: while a code is still live, the page is only drawn again.
        boolean live = authSession.getAuthNote(NOTE_HASH) != null
            && Time.currentTime() <= longNote(authSession, NOTE_EXPIRES);
        if (!live) {
            deliver(context, user);
        }
        context.challenge(page(context, null, null));
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        MultivaluedMap<String, String> form = context.getHttpRequest().getDecodedFormParameters();
        AuthenticationSessionModel authSession = context.getAuthenticationSession();

        if (form.containsKey("resend")) {
            long last = longNote(authSession, NOTE_LAST_SENT);
            if (Time.currentTime() - last < ResetCodes.RESEND_GAP_SECONDS) {
                context.challenge(page(context, null, "lpResetWait"));
                return;
            }
            deliver(context, context.getUser());
            context.challenge(page(context, null, "lpResetResent"));
            return;
        }

        if (Time.currentTime() > longNote(authSession, NOTE_EXPIRES)) {
            context.getEvent().user(context.getUser()).error(Errors.EXPIRED_CODE);
            context.challenge(page(context, "lpResetCodeExpired", null));
            return;
        }
        int attempts = (int) longNote(authSession, NOTE_ATTEMPTS);
        if (attempts >= ResetCodes.MAX_ATTEMPTS) {
            context.challenge(page(context, "lpResetTooManyAttempts", null));
            return;
        }
        boolean right = context.getUser() != null && ResetCodes.matches(authSession.getAuthNote(NOTE_SALT),
            authSession.getAuthNote(NOTE_HASH), form.getFirst("code"));
        if (!right) {
            authSession.setAuthNote(NOTE_ATTEMPTS, Integer.toString(attempts + 1));
            context.getEvent().user(context.getUser()).error(Errors.INVALID_CODE);
            context.challenge(page(context,
                attempts + 1 >= ResetCodes.MAX_ATTEMPTS ? "lpResetTooManyAttempts" : "lpResetWrongCode", null));
            return;
        }
        // Spent: the same code cannot be used twice.
        authSession.removeAuthNote(NOTE_HASH);
        authSession.removeAuthNote(NOTE_SALT);
        context.success();
    }

    /** Sends the link and the code where the account has somewhere to send them, and sets up a code to check. */
    private void deliver(AuthenticationFlowContext context, UserModel user) {
        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        String code = ResetCodes.newCode();
        String salt = ResetCodes.newSalt();
        // A code is set up for every request, an account or not: the page and its answers do not differ.
        authSession.setAuthNote(NOTE_SALT, salt);
        authSession.setAuthNote(NOTE_HASH, ResetCodes.hash(salt, code));
        authSession.setAuthNote(NOTE_EXPIRES, Long.toString(Time.currentTime() + ResetCodes.LIFETIME_SECONDS));
        authSession.setAuthNote(NOTE_ATTEMPTS, "0");
        authSession.setAuthNote(NOTE_LAST_SENT, Long.toString(Time.currentTime()));
        if (user == null || !user.isEnabled()) {
            context.getEvent().clone().event(EventType.SEND_RESET_PASSWORD).error(Errors.USER_NOT_FOUND);
            return;
        }
        if (!withinHourlyCap(context.getSession(), context.getRealm(), user)) {
            context.getEvent().clone().event(EventType.SEND_RESET_PASSWORD).user(user).error("too_many_reset_requests");
            return;
        }
        if (user.getEmail() != null && !user.getEmail().isBlank()) {
            emailLink(context, user);
        }
        String party = user.getFirstAttribute("party_id");
        String tenant = user.getFirstAttribute("tenant_id");
        if (party != null && tenant != null) {
            String language = context.getSession().getContext().resolveLocale(user).getLanguage();
            texter.send(context.getRealm().getName(), tenant, party, code, language);
            context.getEvent().clone().event(EventType.SEND_RESET_PASSWORD).user(user)
                .detail(Details.USERNAME, user.getUsername()).detail("channel", "sms").success();
        }
    }

    /** Keycloak's own reset email, word for word what its email step sends, with the link that resumes this flow. */
    private void emailLink(AuthenticationFlowContext context, UserModel user) {
        RealmModel realm = context.getRealm();
        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        int lifespan = realm.getActionTokenGeneratedByUserLifespan(ResetCredentialsActionToken.TOKEN_TYPE);
        String encodedSession = AuthenticationSessionCompoundId.fromAuthSession(authSession).getEncodedId();
        ResetCredentialsActionToken token = new ResetCredentialsActionToken(user.getId(), user.getEmail(),
            Time.currentTime() + lifespan, encodedSession, authSession.getClient().getClientId());
        String link = UriBuilder.fromUri(context.getActionTokenUrl(
            token.serialize(context.getSession(), realm, context.getUriInfo()))).build().toString();
        try {
            context.getSession().getProvider(EmailTemplateProvider.class).setRealm(realm).setUser(user)
                .setAuthenticationSession(authSession).sendPasswordReset(link, TimeUnit.SECONDS.toMinutes(lifespan));
            context.getEvent().clone().event(EventType.SEND_RESET_PASSWORD).user(user)
                .detail(Details.USERNAME, user.getUsername()).detail(Details.EMAIL, user.getEmail()).success();
        } catch (EmailException e) {
            log.warnf("Could not email a reset link to user %s: %s", user.getId(), e.getMessage());
            context.getEvent().clone().event(EventType.SEND_RESET_PASSWORD).user(user).error(Errors.EMAIL_SEND_FAILED);
        }
    }

    /** At most three sends an hour to one account, across browsers: counted where every Keycloak node can see it. */
    private static boolean withinHourlyCap(KeycloakSession session, RealmModel realm, UserModel user) {
        SingleUseObjectProvider store = session.singleUseObjects();
        String key = "lp-reset-sends:" + realm.getId() + ":" + user.getId();
        long now = Time.currentTime();
        Map<String, String> notes = store.get(key);
        List<Long> sends = new ArrayList<>(ResetCodes.recentSends(notes == null ? null : notes.get("at"), now));
        if (!ResetCodes.maySend(sends)) return false;
        sends.add(now);
        Map<String, String> next = new HashMap<>();
        next.put("at", ResetCodes.store(sends));
        if (notes != null) store.remove(key);
        store.put(key, 3600, next);
        return true;
    }

    private Response page(AuthenticationFlowContext context, String error, String info) {
        var form = context.form().setAttribute("lpResetCodeDigits", ResetCodes.DIGITS)
            .setAttribute("lpResetMinutes", ResetCodes.LIFETIME_SECONDS / 60);
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
        return false;
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
