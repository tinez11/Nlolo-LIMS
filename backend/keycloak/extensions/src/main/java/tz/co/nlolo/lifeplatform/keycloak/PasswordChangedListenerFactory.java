package tz.co.nlolo.lifeplatform.keycloak;

import org.keycloak.Config;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

/**
 * Registers {@link PasswordChangedListener} (2026-11-06). The realm turns it on by naming
 * {@code lifeplatform-password-changed} in {@code eventsListeners}. Where the platform is comes from
 * {@code LP_PASSWORD_CHANGED_URL}; the signing secret is the reset text's, {@code LP_RESET_SMS_SECRET}.
 */
public class PasswordChangedListenerFactory implements EventListenerProviderFactory {

    public static final String PROVIDER_ID = "lifeplatform-password-changed";

    private PasswordChangeNotifier notifier;

    @Override
    public EventListenerProvider create(KeycloakSession session) {
        return new PasswordChangedListener(session, notifier);
    }

    @Override
    public void init(Config.Scope config) {
        notifier = new PasswordChangeNotifier(System.getenv("LP_PASSWORD_CHANGED_URL"), System.getenv("LP_RESET_SMS_SECRET"));
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {}

    @Override
    public void close() {}

    @Override
    public String getId() {
        return PROVIDER_ID;
    }
}
