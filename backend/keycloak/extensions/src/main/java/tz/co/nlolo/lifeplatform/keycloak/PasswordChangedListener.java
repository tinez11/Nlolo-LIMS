package tz.co.nlolo.lifeplatform.keycloak;

import org.jboss.logging.Logger;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

/**
 * Hears every password change and asks the platform to text the person about it (2026-11-06).
 *
 * <p>{@code UPDATE_PASSWORD} only: it is raised for a change from the account menu, for the new password at the end of
 * a reset, and for the first change after a temporary password. Keycloak 26 also raises {@code UPDATE_CREDENTIAL} for
 * the same change -- listening to both would text the person twice. An administrator's reset raises an admin event,
 * not this one, and needs no notice: the same act already texts the person their temporary password.
 *
 * <p>Never throws: a notice that fails must not undo or block the change it describes.
 */
final class PasswordChangedListener implements EventListenerProvider {

    private static final Logger log = Logger.getLogger(PasswordChangedListener.class);

    private final KeycloakSession session;
    private final PasswordChangeNotifier notifier;

    PasswordChangedListener(KeycloakSession session, PasswordChangeNotifier notifier) {
        this.session = session;
        this.notifier = notifier;
    }

    @Override
    public void onEvent(Event event) {
        if (event.getType() != EventType.UPDATE_PASSWORD || event.getUserId() == null) return;
        try {
            RealmModel realm = session.realms().getRealm(event.getRealmId());
            UserModel user = realm == null ? null : session.users().getUserById(realm, event.getUserId());
            if (user == null) return;
            String tenant = user.getFirstAttribute("tenant_id");
            if (tenant == null || tenant.isBlank()) return;
            String language = session.getContext().resolveLocale(user).getLanguage();
            notifier.passwordChanged(tenant, user.getId(), user.getFirstAttribute("party_id"), language);
        } catch (RuntimeException e) {
            log.warnf("A password-changed notice for user %s was not sent: %s", event.getUserId(), e.toString());
        }
    }

    @Override
    public void onEvent(AdminEvent event, boolean includeRepresentation) {
        // An administrator's reset texts the temporary password itself; see the class comment.
    }

    @Override
    public void close() {}
}
