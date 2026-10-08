package tz.co.nlolo.lifeplatform.omnichannel.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tz.co.nlolo.lifeplatform.omnichannel.api.PortalAccessRefusedException;
import tz.co.nlolo.lifeplatform.omnichannel.application.CustomerAccounts;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Customer logins through the Keycloak Admin API (2026-10-08), as the {@code lifeplatform-admin} service account of the
 * {@code customers} realm -- a confidential client holding only {@code manage-users} there, so the platform never holds
 * the Keycloak super-admin password. Its secret comes from the environment ({@code KEYCLOAK_PORTAL_ADMIN_SECRET}).
 */
@Component
public class KeycloakCustomerAccounts implements CustomerAccounts {

    private final RestClient http;
    private final String realm;
    private final String clientId;
    private final String clientSecret;
    private final String portalClientId;
    private final String portalUrl;

    private String token;
    private Instant tokenExpires = Instant.EPOCH;

    public KeycloakCustomerAccounts(@Value("${app.portal.keycloak.base-url}") String baseUrl,
                                    @Value("${app.portal.keycloak.realm}") String realm,
                                    @Value("${app.portal.keycloak.client-id}") String clientId,
                                    @Value("${app.portal.keycloak.client-secret}") String clientSecret,
                                    @Value("${app.portal.keycloak.portal-client-id}") String portalClientId,
                                    @Value("${app.portal.url}") String portalUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(10000);
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.realm = realm;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.portalClientId = portalClientId;
        this.portalUrl = portalUrl;
    }

    @Override
    public String create(String username, String email, String displayName, UUID tenantId, UUID partyId) {
        Map<String, Object> user = new java.util.LinkedHashMap<>();
        user.put("username", username);
        user.put("enabled", true);
        user.put("firstName", displayName);
        if (email != null) {
            user.put("email", email);
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
            return path.substring(path.lastIndexOf('/') + 1);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 409) {
                return existingLoginOf(username, partyId);
            }
            throw unavailable(e);
        }
    }

    /**
     * A login with this username exists. If it is this client's own -- an earlier invite that created the login and then
     * failed (the email could not be sent, say) -- it is reused; anyone else's is a refusal, never a takeover.
     */
    @SuppressWarnings("unchecked")
    private String existingLoginOf(String username, UUID partyId) {
        List<Map<String, Object>> found = http.get()
            .uri("/admin/realms/{realm}/users?username={u}&exact=true", realm, username)
            .header("Authorization", "Bearer " + token()).retrieve().body(List.class);
        if (found != null && !found.isEmpty()) {
            Map<String, Object> user = found.get(0);
            Object attributes = user.get("attributes");
            if (attributes instanceof Map<?, ?> map && map.get("party_id") instanceof List<?> ids
                    && ids.contains(partyId.toString())) {
                return (String) user.get("id");
            }
        }
        throw new PortalAccessRefusedException("A portal login named " + username + " already exists for someone else."
            + " Correct the client's email or phone number, or ask IT to look at that login.", true);
    }

    @Override
    public void sendSetPasswordLink(String userId) {
        try {
            http.put().uri("/admin/realms/{realm}/users/{id}/execute-actions-email?client_id={c}&redirect_uri={r}"
                    + "&lifespan=259200", realm, userId, portalClientId, portalUrl)
                .header("Authorization", "Bearer " + token()).contentType(MediaType.APPLICATION_JSON)
                .body(List.of("UPDATE_PASSWORD")).retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new PortalAccessRefusedException("The login was created, but the email could not be sent ("
                + e.getStatusCode().value() + "). Check the email address and the mail server, then re-send the invite.");
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
            http.put().uri("/admin/realms/{realm}/users/{id}", realm, userId)
                .header("Authorization", "Bearer " + token()).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("enabled", enabled)).retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw unavailable(e);
        }
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
        HttpStatusCode status = e.getStatusCode();
        return new IllegalStateException("The identity provider refused the request (" + status.value() + "): "
            + e.getResponseBodyAsString());
    }
}
