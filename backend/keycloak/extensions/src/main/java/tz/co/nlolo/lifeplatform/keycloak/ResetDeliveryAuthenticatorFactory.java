package tz.co.nlolo.lifeplatform.keycloak;

import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

import java.util.List;

/**
 * Registers {@link ResetDeliveryAuthenticator} with Keycloak (2026-10-28). Where the platform is and the secret it
 * checks come from the environment, as every other setting of the stack does: LP_RESET_SMS_URL and
 * LP_RESET_SMS_SECRET. Without them the link is still emailed and no code is texted.
 */
public class ResetDeliveryAuthenticatorFactory implements AuthenticatorFactory {

    public static final String PROVIDER_ID = "lifeplatform-reset-delivery";

    private ResetCodeTexter texter;

    @Override
    public void init(Config.Scope config) {
        texter = new ResetCodeTexter(System.getenv("LP_RESET_SMS_URL"), System.getenv("LP_RESET_SMS_SECRET"));
    }

    @Override
    public Authenticator create(KeycloakSession session) {
        return new ResetDeliveryAuthenticator(texter);
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "Send reset link and text code";
    }

    @Override
    public String getHelpText() {
        return "Emails a reset link to the address on file and texts a six-digit code to the phone the platform holds"
            + " for the account, then asks for the code. Shows the same page whatever was typed.";
    }

    @Override
    public String getReferenceCategory() {
        return null;
    }

    @Override
    public boolean isConfigurable() {
        return false;
    }

    @Override
    public AuthenticationExecutionModel.Requirement[] getRequirementChoices() {
        return new AuthenticationExecutionModel.Requirement[] {AuthenticationExecutionModel.Requirement.REQUIRED};
    }

    @Override
    public boolean isUserSetupAllowed() {
        return false;
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return List.of();
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {}

    @Override
    public void close() {}
}
