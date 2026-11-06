package tz.co.nlolo.lifeplatform.iam.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tz.co.nlolo.lifeplatform.iam.api.AccessChangeRefusedException;
import tz.co.nlolo.lifeplatform.iam.application.StaffLogins;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.iam.api.AccessChangeRefusedException.Reason.LOGIN_PROVIDER;
import static tz.co.nlolo.lifeplatform.iam.api.AccessChangeRefusedException.Reason.USERNAME_TAKEN;

/**
 * Staff logins through the Keycloak Admin API (2026-10-26), as the {@code lifeplatform-admin} service account of the one
 * realm, {@code lifeplatform} (2026-11-06) -- a confidential client holding only {@code manage-users} and
 * {@code view-users}. Same shape as the customer portal's {@code KeycloakCustomerAccounts}; its secret comes from
 * {@code KEYCLOAK_ADMIN_CLIENT_SECRET}.
 *
 * <p>No job roles are written to Keycloak: the platform holds them. The login carries the {@code staff} workspace role
 * -- which opens the staff console in the one realm -- and what the token needs, {@code tenant_id} and
 * {@code home_branch}. A staff login is never joined to an existing account (decision U10): a username or email that
 * is taken is a refusal, even when it belongs to the same person's portal or agent login.
 */
@Component
public class KeycloakStaffLogins implements StaffLogins {

    private final RestClient http;
    /** The workspace role every staff login holds in the one realm (2026-11-06). */
    private static final String WORKSPACE_ROLE = "staff";

    private final String realm;
    private final String clientId;
    private final String clientSecret;
    private final String consoleClientId;
    private final String consoleUrl;

    private String token;
    private Instant tokenExpires = Instant.EPOCH;

    public KeycloakStaffLogins(@Value("${app.staff-admin.keycloak.base-url}") String baseUrl,
                               @Value("${app.staff-admin.keycloak.realm}") String realm,
                               @Value("${app.staff-admin.keycloak.client-id}") String clientId,
                               @Value("${app.staff-admin.keycloak.client-secret}") String clientSecret,
                               @Value("${app.staff-admin.keycloak.console-client-id}") String consoleClientId,
                               @Value("${app.staff-admin.console-url}") String consoleUrl) {
        this.consoleClientId = consoleClientId;
        this.consoleUrl = consoleUrl;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(10000);
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.realm = realm;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    public UUID create(NewLogin login) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("username", login.username());
        user.put("enabled", true);
        user.put("firstName", login.firstName());
        user.put("lastName", login.lastName());
        if (login.email() != null) {
            user.put("email", login.email());
            user.put("emailVerified", true);
        }
        Map<String, List<String>> attributes = new LinkedHashMap<>();
        attributes.put("tenant_id", List.of(login.tenantId().toString()));
        if (login.homeBranch() != null) attributes.put("home_branch", List.of(login.homeBranch()));
        user.put("attributes", attributes);
        user.put("requiredActions", List.of("UPDATE_PASSWORD"));
        try {
            URI location = http.post().uri("/admin/realms/{realm}/users", realm)
                .header("Authorization", "Bearer " + token()).contentType(MediaType.APPLICATION_JSON).body(user)
                .retrieve().toBodilessEntity().getHeaders().getLocation();
            if (location == null) throw new IllegalStateException("Keycloak created the user but returned no Location");
            String path = location.getPath();
            UUID userId = UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
            try {
                grantStaffWorkspace(userId.toString());
            } catch (RestClientResponseException e) {
                // A login that opens nothing would only block a retry with "username taken": remove it, then refuse.
                http.delete().uri("/admin/realms/{realm}/users/{id}", realm, userId)
                    .header("Authorization", "Bearer " + token()).retrieve().toBodilessEntity();
                throw refused(e);
            }
            return userId;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 409) {
                throw new AccessChangeRefusedException(USERNAME_TAKEN,
                    "A login named " + login.username() + " or with that email already exists. Choose another.");
            }
            throw refused(e);
        }
    }

    @Override
    public void sendSetPasswordLink(UUID userId) {
        try {
            http.put().uri("/admin/realms/{realm}/users/{id}/execute-actions-email?client_id={c}&redirect_uri={r}"
                    + "&lifespan=259200", realm, userId, consoleClientId, consoleUrl)
                .header("Authorization", "Bearer " + token()).contentType(MediaType.APPLICATION_JSON)
                .body(List.of("UPDATE_PASSWORD")).retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new AccessChangeRefusedException(LOGIN_PROVIDER, "The login is ready, but the email could not be sent ("
                + e.getStatusCode().value() + "). Check the address and the mail server, then send a new password.");
        }
    }

    @Override
    public void setTemporaryPassword(UUID userId, String password) {
        try {
            http.put().uri("/admin/realms/{realm}/users/{id}/reset-password", realm, userId)
                .header("Authorization", "Bearer " + token()).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("type", "password", "value", password, "temporary", true)).retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw refused(e);
        }
    }

    @Override
    public void setEnabled(UUID userId, boolean enabled) {
        try {
            http.put().uri("/admin/realms/{realm}/users/{id}", realm, userId)
                .header("Authorization", "Bearer " + token()).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("enabled", enabled)).retrieve().toBodilessEntity();
            if (!enabled) {
                http.post().uri("/admin/realms/{realm}/users/{id}/logout", realm, userId)
                    .header("Authorization", "Bearer " + token()).retrieve().toBodilessEntity();
            }
        } catch (RestClientResponseException e) {
            throw refused(e);
        }
    }

    /**
     * Gives the new login the {@code staff} workspace role (2026-11-06). Read from the account's available realm roles --
     * reachable with view-users, unlike {@code /roles/{name}}. A failure here reaches {@code create}'s catch: refused.
     */
    @SuppressWarnings("unchecked")
    private void grantStaffWorkspace(String userId) {
        List<Map<String, Object>> available = http.get()
            .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm/available", realm, userId)
            .header("Authorization", "Bearer " + token()).retrieve().body(List.class);
        Map<String, Object> role = available == null ? null
            : available.stream().filter(r -> WORKSPACE_ROLE.equals(r.get("name"))).findFirst().orElse(null);
        if (role == null) return; // already held
        http.post().uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", realm, userId)
            .header("Authorization", "Bearer " + token()).contentType(MediaType.APPLICATION_JSON)
            .body(List.of(role)).retrieve().toBodilessEntity();
    }

    /** The service account's token, reused until shortly before it expires. */
    @SuppressWarnings("unchecked")
    private synchronized String token() {
        if (token != null && Instant.now().isBefore(tokenExpires)) return token;
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        Map<String, Object> body;
        try {
            body = http.post().uri("/realms/{realm}/protocol/openid-connect/token", realm)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(Map.class);
        } catch (RestClientResponseException e) {
            throw refused(e);
        }
        if (body == null || !(body.get("access_token") instanceof String access)) {
            throw new IllegalStateException("Keycloak returned no access token for " + clientId);
        }
        long expiresIn = body.get("expires_in") instanceof Number n ? n.longValue() : 60;
        token = access;
        tokenExpires = Instant.now().plusSeconds(Math.max(10, expiresIn - 30));
        return token;
    }

    private static AccessChangeRefusedException refused(RestClientResponseException e) {
        return new AccessChangeRefusedException(LOGIN_PROVIDER,
            "The login service refused the change (" + e.getStatusCode().value() + "). Nothing was changed on the platform.");
    }
}
