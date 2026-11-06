package tz.co.nlolo.lifeplatform.distribution.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tz.co.nlolo.lifeplatform.distribution.api.InvalidAgentStateException;
import tz.co.nlolo.lifeplatform.distribution.application.AgentLogins;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent logins through the Keycloak Admin API (2026-10-28), as the {@code lifeplatform-admin} service account of the one
 * realm, {@code lifeplatform} (2026-11-06) -- a confidential client holding only {@code manage-users} and
 * {@code view-users}. Its secret comes from the environment ({@code KEYCLOAK_ADMIN_CLIENT_SECRET}). An agent login is
 * the {@code agent} workspace role on an account, which the same person's portal access may share. The same shape as
 * {@code KeycloakCustomerAccounts}.
 */
@Component
public class KeycloakAgentLogins implements AgentLogins {

    private final RestClient http;
    /** The workspace role this adapter gives and takes in the one realm (2026-11-06). */
    private static final String WORKSPACE_ROLE = "agent";

    private final String realm;
    private final String clientId;
    private final String clientSecret;
    private final String consoleClientId;
    private final String consoleUrl;

    private String token;
    private Instant tokenExpires = Instant.EPOCH;

    public KeycloakAgentLogins(@Value("${app.agent-logins.keycloak.base-url}") String baseUrl,
                               @Value("${app.agent-logins.keycloak.realm}") String realm,
                               @Value("${app.agent-logins.keycloak.client-id}") String clientId,
                               @Value("${app.agent-logins.keycloak.client-secret}") String clientSecret,
                               @Value("${app.agent-logins.keycloak.console-client-id}") String consoleClientId,
                               @Value("${app.agent-logins.console-url}") String consoleUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(10000);
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.realm = realm;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.consoleClientId = consoleClientId;
        this.consoleUrl = consoleUrl;
    }

    @Override
    public String create(String username, String email, String displayName, UUID tenantId, UUID partyId) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("username", username);
        user.put("enabled", true);
        user.put("firstName", displayName);
        if (email != null) {
            user.put("email", email);
            user.put("emailVerified", true);
        }
        user.put("attributes", Map.of("tenant_id", List.of(tenantId.toString()), "party_id", List.of(partyId.toString())));
        user.put("requiredActions", List.of("UPDATE_PASSWORD"));
        try {
            URI location = http.post().uri("/admin/realms/{realm}/users", realm)
                .header("Authorization", "Bearer " + token()).contentType(MediaType.APPLICATION_JSON).body(user)
                .retrieve().toBodilessEntity().getHeaders().getLocation();
            if (location == null) {
                throw new IllegalStateException("Keycloak created the user but returned no Location");
            }
            String path = location.getPath();
            String userId = path.substring(path.lastIndexOf('/') + 1);
            grantWorkspace(userId);
            return userId;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 409) {
                String userId = existingLoginOf(username, email, partyId);
                try {
                    grantWorkspace(userId);
                } catch (RestClientResponseException grant) {
                    throw unavailable(grant);
                }
                return userId;
            }
            throw unavailable(e);
        }
    }

    /**
     * The same person's earlier login -- their own agent login, or their customer-portal login (one account, two
     * workspaces, matched by party_id; 2026-11-06) -- is joined. Anyone else's username, a staff login included (staff
     * carry no party_id), is a refusal, never a takeover.
     *
     * <p>Looked up by username AND by email: emails are unique in the one realm, so the conflict may be the address
     * alone -- the person's portal login keeps another username (2026-11-06, found by the agent-lifecycle e2e).
     */
    @SuppressWarnings("unchecked")
    private String existingLoginOf(String username, String email, UUID partyId) {
        List<Map<String, Object>> found = new java.util.ArrayList<>();
        List<Map<String, Object>> byUsername = http.get()
            .uri("/admin/realms/{realm}/users?username={u}&exact=true", realm, username)
            .header("Authorization", "Bearer " + token()).retrieve().body(List.class);
        if (byUsername != null) found.addAll(byUsername);
        if (email != null) {
            List<Map<String, Object>> byEmail = http.get()
                .uri("/admin/realms/{realm}/users?email={e}&exact=true", realm, email.toLowerCase(java.util.Locale.ROOT))
                .header("Authorization", "Bearer " + token()).retrieve().body(List.class);
            if (byEmail != null) found.addAll(byEmail);
        }
        for (Map<String, Object> user : found) {
            if (user.get("attributes") instanceof Map<?, ?> map && map.get("party_id") instanceof List<?> ids
                    && ids.contains(partyId.toString())) {
                return (String) user.get("id");
            }
        }
        throw new InvalidAgentStateException("An agent login named " + username + " already exists for someone else."
            + " Correct the agent's email or phone number, or ask IT to look at that login.");
    }

    @Override
    public void sendSetPasswordLink(String userId) {
        try {
            http.put().uri("/admin/realms/{realm}/users/{id}/execute-actions-email?client_id={c}&redirect_uri={r}"
                    + "&lifespan=259200", realm, userId, consoleClientId, consoleUrl)
                .header("Authorization", "Bearer " + token()).contentType(MediaType.APPLICATION_JSON)
                .body(List.of("UPDATE_PASSWORD")).retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new InvalidAgentStateException("The login is ready, but the email could not be sent ("
                + e.getStatusCode().value() + "). Check the agent's email address and the mail server, then send a new"
                + " password.");
        }
    }

    @Override
    public void setTemporaryPassword(String userId, String password) {
        try {
            http.put().uri("/admin/realms/{realm}/users/{id}/reset-password", realm, userId)
                .header("Authorization", "Bearer " + token()).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("type", "password", "value", password, "temporary", true)).retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw unavailable(e);
        }
    }

    @Override
    public void setEnabled(String userId, boolean enabled) {
        try {
            if (enabled) {
                grantWorkspace(userId);
            } else {
                // Take the agent workspace away, not the account: the same person's portal access stays (2026-11-06).
                revokeWorkspace(userId);
                http.post().uri("/admin/realms/{realm}/users/{id}/logout", realm, userId)
                    .header("Authorization", "Bearer " + token()).retrieve().toBodilessEntity();
            }
        } catch (RestClientResponseException e) {
            throw unavailable(e);
        }
    }

    /**
     * The account's mapping of {@link #WORKSPACE_ROLE}, from its available ({@code "/available"}) or assigned
     * ({@code ""}) realm roles -- endpoints the service account's view-users reaches, unlike {@code /roles/{name}}.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> workspaceRoleIn(String userId, String list) {
        List<Map<String, Object>> roles = http.get()
            .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm" + list, realm, userId)
            .header("Authorization", "Bearer " + token()).retrieve().body(List.class);
        return roles == null ? null
            : roles.stream().filter(r -> WORKSPACE_ROLE.equals(r.get("name"))).findFirst().orElse(null);
    }

    private void grantWorkspace(String userId) {
        Map<String, Object> role = workspaceRoleIn(userId, "/available");
        if (role == null) return; // already held
        http.post().uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", realm, userId)
            .header("Authorization", "Bearer " + token()).contentType(MediaType.APPLICATION_JSON)
            .body(List.of(role)).retrieve().toBodilessEntity();
    }

    private void revokeWorkspace(String userId) {
        Map<String, Object> role = workspaceRoleIn(userId, "");
        if (role == null) return; // not held
        http.method(HttpMethod.DELETE).uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", realm, userId)
            .header("Authorization", "Bearer " + token()).contentType(MediaType.APPLICATION_JSON)
            .body(List.of(role)).retrieve().toBodilessEntity();
    }

    /** The service account's token, reused until shortly before it expires. */
    @SuppressWarnings("unchecked")
    private synchronized String token() {
        if (token != null && Instant.now().isBefore(tokenExpires)) {
            return token;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        Map<String, Object> body;
        try {
            body = http.post().uri("/realms/{realm}/protocol/openid-connect/token", realm)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(Map.class);
        } catch (RestClientResponseException e) {
            throw unavailable(e);
        }
        if (body == null || !(body.get("access_token") instanceof String access)) {
            throw new IllegalStateException("Keycloak returned no access token for " + clientId);
        }
        long expiresIn = body.get("expires_in") instanceof Number n ? n.longValue() : 60;
        token = access;
        tokenExpires = Instant.now().plusSeconds(Math.max(10, expiresIn - 30));
        return token;
    }

    private static IllegalStateException unavailable(RestClientResponseException e) {
        return new IllegalStateException("The identity provider refused the request (" + e.getStatusCode().value()
            + "): " + e.getResponseBodyAsString());
    }
}
