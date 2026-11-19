package tz.co.nlolo.lifeplatform.iam.infrastructure;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.AuthentikClient;
import tz.co.nlolo.lifeplatform.iam.api.AccessChangeRefusedException;
import tz.co.nlolo.lifeplatform.iam.application.StaffLogins;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.iam.api.AccessChangeRefusedException.Reason.LOGIN_PROVIDER;
import static tz.co.nlolo.lifeplatform.iam.api.AccessChangeRefusedException.Reason.USERNAME_TAKEN;

/**
 * Staff logins in Authentik (2026-11-19, ticket 03 of the cut-over; replaces the Keycloak adapter), through the
 * backend's service account, which may only manage logins it created.
 *
 * <p>No job roles are written to Authentik: the platform holds them. The login joins the {@code staff} group, which
 * opens the staff console, and carries what the token needs, {@code tenant_id} and {@code home_branch}. A staff login is
 * never joined to an existing account (decision U10): a taken username is a refusal.
 *
 * <p>A temporary password is Keycloak's UPDATE_PASSWORD required action rebuilt: the password, then the login's
 * {@code reset_password} attribute, which the sign-in flow's check stage reads to make the person choose their own.
 */
@Component
public class AuthentikStaffLogins implements StaffLogins {

    private static final String WORKSPACE = "staff";
    private static final String PATH = "lifeplatform/staff";

    private final AuthentikClient authentik;

    public AuthentikStaffLogins(AuthentikClient authentik) {
        this.authentik = authentik;
    }

    @Override
    public UUID create(NewLogin login) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("tenant_id", login.tenantId().toString());
        if (login.homeBranch() != null) attributes.put("home_branch", login.homeBranch());
        // Until an administrator issues a password, there is none to sign in with; when there is, it is temporary.
        attributes.put("reset_password", true);
        AuthentikClient.User user;
        try {
            user = authentik.createUser(login.username(), login.firstName() + " " + login.lastName(), PATH,
                login.email(), attributes);
        } catch (AuthentikClient.UsernameTaken e) {
            throw new AccessChangeRefusedException(USERNAME_TAKEN,
                "A login named " + login.username() + " already exists. Choose another.");
        } catch (AuthentikClient.Refused e) {
            throw refused(e);
        }
        try {
            authentik.addToGroup(WORKSPACE, user.pk());
        } catch (AuthentikClient.Refused e) {
            // A login that opens nothing would only block a retry with "username taken": remove it, then refuse.
            authentik.deleteUser(user.pk());
            throw refused(e);
        }
        return user.uuid();
    }

    /** Email is never a channel here, so there is no emailed link: the Users screen issues a temporary password. */
    @Override
    public void sendSetPasswordLink(UUID userId) {
        throw new AccessChangeRefusedException(LOGIN_PROVIDER,
            "Emailed set-your-password links are not available. Issue a temporary password instead.");
    }

    @Override
    public void setTemporaryPassword(UUID userId, String password) {
        try {
            int pk = authentik.user(userId).pk();
            authentik.setPassword(pk, password);
            authentik.mergeAttributes(pk, Map.of("reset_password", true));
        } catch (AuthentikClient.Refused e) {
            throw refused(e);
        }
    }

    @Override
    public void setEnabled(UUID userId, boolean enabled) {
        try {
            authentik.setActive(authentik.user(userId).pk(), enabled);
        } catch (AuthentikClient.Refused e) {
            throw refused(e);
        }
    }

    private static AccessChangeRefusedException refused(AuthentikClient.Refused e) {
        String why = e.status() == 404
            ? "The login service has no login it may change for this person (logins made outside the platform are"
                + " changed on the server)."
            : "The login service refused the change (" + e.status() + ").";
        return new AccessChangeRefusedException(LOGIN_PROVIDER, why + " Nothing was changed on the platform.");
    }
}
