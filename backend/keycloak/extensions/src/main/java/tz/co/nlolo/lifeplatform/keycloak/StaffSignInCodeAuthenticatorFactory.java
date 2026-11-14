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
 * Registers {@link StaffSignInCodeAuthenticator} (2026-11-14). Where the platform is comes from LP_STAFF_CODE_SMS_URL;
 * the request is signed with the reset code's shared secret, LP_RESET_SMS_SECRET. Without them no code can be texted,
 * and staff cannot sign in -- the step never lets anyone in on a failure.
 */
public class StaffSignInCodeAuthenticatorFactory implements AuthenticatorFactory {

    public static final String PROVIDER_ID = "lifeplatform-staff-sign-in-code";

    private StaffCodeTexter texter;

    @Override
    public void init(Config.Scope config) {
        texter = new StaffCodeTexter(System.getenv("LP_STAFF_CODE_SMS_URL"), System.getenv("LP_RESET_SMS_SECRET"));
    }

    @Override
    public Authenticator create(KeycloakSession session) {
        return new StaffSignInCodeAuthenticator(texter);
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "Staff sign-in code by text";
    }

    @Override
    public String getHelpText() {
        return "For staff only: texts a six-digit code to the phone on the staff record and asks for it after the"
            + " password. Lets others through. Off when the platform says codes are switched off.";
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
