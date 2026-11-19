package tz.co.nlolo.lifeplatform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authentik's users API, as the backend's service account (2026-11-19, ticket 03 of the Authentik cut-over). Shared by
 * the staff, agent and customer login adapters.
 *
 * <p>The traps it hides:
 * <ul>
 *   <li><b>Two ids.</b> The API addresses a user by an integer {@code pk}; the platform keeps the token's UUID
 *   {@code sub}. {@link #user(UUID)} looks one up from the other.</li>
 *   <li><b>A taken username</b> is a 400 with a {@code username} field error, not a 409: {@link UsernameTaken}.</li>
 *   <li><b>Attributes are replaced whole</b> by a PATCH, so {@link #mergeAttributes} reads them first; otherwise
 *   {@code tenant_id} and {@code party_id} are lost.</li>
 *   <li><b>Narrow rights.</b> The token sees only users it created itself: anyone else answers 404, the same as a user
 *   that doesn't exist. Every refusal is a {@link Refused} carrying the status.</li>
 * </ul>
 * The token is non-expiring and held as {@code AUTHENTIK_API_TOKEN}.
 */
@Component
public class AuthentikClient {

    /** A login as the API reports it. */
    public record User(int pk, UUID uuid, String username, boolean active, Map<String, Object> attributes) {}

    /** Authentik refused the call; {@code status} is its HTTP status (404 also for a user this token may not see). */
    public static class Refused extends RuntimeException {
        private final int status;

        public Refused(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    /** The username is already a login. */
    public static class UsernameTaken extends Refused {
        public UsernameTaken(String username) {
            super(400, "The username " + username + " is already a login");
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient http;
    private final Map<String, String> groupIds = new ConcurrentHashMap<>();

    @Autowired
    public AuthentikClient(@Value("${app.authentik.base-url}") String baseUrl,
                           @Value("${app.authentik.api-token}") String apiToken) {
        // The JDK HttpClient, not HttpURLConnection: Authentik changes a user with PATCH, which HttpURLConnection refuses.
        // Plain HTTP/1.1, with no cleartext HTTP/2 upgrade offered. Buffered, so each body goes whole with its length:
        // Authentik drops a chunked body (found 2026-11-19: every create answered 400 "This field is required.").
        JdkClientHttpRequestFactory jdk = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build());
        jdk.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().baseUrl(baseUrl + "/api/v3")
            .requestFactory(new BufferingClientHttpRequestFactory(jdk))
            .defaultHeader("Authorization", "Bearer " + apiToken).build();
    }

    /** Creates an active login. {@code email} may be null. */
    public User createUser(String username, String name, String path, String email, Map<String, Object> attributes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("name", name);
        body.put("path", path);
        body.put("is_active", true);
        if (email != null) body.put("email", email);
        body.put("attributes", attributes);
        try {
            return toUser(http.post().uri("/core/users/").contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(JsonNode.class));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 400 && usernameNotUnique(e)) throw new UsernameTaken(username);
            throw refused(e);
        }
    }

    /** The login with this UUID, if this token may see it. */
    public Optional<User> findUser(UUID uuid) {
        JsonNode page = call(() -> http.get().uri("/core/users/?uuid={uuid}", uuid).retrieve().body(JsonNode.class));
        JsonNode results = page == null ? null : page.path("results");
        return results == null || results.isEmpty() ? Optional.empty() : Optional.of(toUser(results.get(0)));
    }

    /** The login with this UUID; {@link Refused} 404 when there is none this token may see. */
    public User user(UUID uuid) {
        return findUser(uuid).orElseThrow(() -> new Refused(404, "No login " + uuid + " that this service may manage"));
    }

    public void setPassword(int pk, String password) {
        call(() -> http.post().uri("/core/users/{pk}/set_password/", pk).contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("password", password)).retrieve().toBodilessEntity());
    }

    /** Opens or closes a login. Closing also ends its sessions and tokens (Authentik does that on deactivation). */
    public void setActive(int pk, boolean active) {
        call(() -> http.patch().uri("/core/users/{pk}/", pk).contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("is_active", active)).retrieve().toBodilessEntity());
    }

    /** Sets some attributes, keeping all the others: Authentik replaces the attributes record whole. */
    public void mergeAttributes(int pk, Map<String, Object> changes) {
        JsonNode current = call(() -> http.get().uri("/core/users/{pk}/", pk).retrieve().body(JsonNode.class));
        Map<String, Object> attributes = new LinkedHashMap<>(toUser(current).attributes());
        attributes.putAll(changes);
        call(() -> http.patch().uri("/core/users/{pk}/", pk).contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("attributes", attributes)).retrieve().toBodilessEntity());
    }

    public void deleteUser(int pk) {
        call(() -> http.delete().uri("/core/users/{pk}/", pk).retrieve().toBodilessEntity());
    }

    /** Grants a workspace: adds the login to the group of that name. */
    public void addToGroup(String group, int userPk) {
        String id = groupId(group);
        call(() -> http.post().uri("/core/groups/{id}/add_user/", id).contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("pk", userPk)).retrieve().toBodilessEntity());
    }

    /** Revokes a workspace: removes the login from the group of that name. */
    public void removeFromGroup(String group, int userPk) {
        String id = groupId(group);
        call(() -> http.post().uri("/core/groups/{id}/remove_user/", id).contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("pk", userPk)).retrieve().toBodilessEntity());
    }

    private String groupId(String name) {
        String cached = groupIds.get(name);
        if (cached != null) return cached;
        JsonNode page = call(() -> http.get().uri("/core/groups/?name={name}&include_users=false", name)
            .retrieve().body(JsonNode.class));
        JsonNode results = page == null ? null : page.path("results");
        if (results == null || results.isEmpty()) throw new Refused(404, "No group " + name + " that this service may use");
        String id = results.get(0).path("pk").asText();
        groupIds.put(name, id);
        return id;
    }

    @SuppressWarnings("unchecked")
    private static User toUser(JsonNode node) {
        if (node == null) throw new Refused(502, "Authentik answered with no user");
        Map<String, Object> attributes = node.hasNonNull("attributes")
            ? JSON.convertValue(node.get("attributes"), LinkedHashMap.class) : new LinkedHashMap<>();
        return new User(node.path("pk").asInt(), UUID.fromString(node.path("uuid").asText()),
            node.path("username").asText(), node.path("is_active").asBoolean(), attributes);
    }

    /** Authentik's field error for a taken username: {"username": ["This field must be unique."]}. */
    private static boolean usernameNotUnique(RestClientResponseException e) {
        try {
            for (JsonNode message : JSON.readTree(e.getResponseBodyAsString()).path("username")) {
                if (message.asText().contains("unique")) return true;
            }
            return false;
        } catch (Exception notJson) {
            return false;
        }
    }

    private static <T> T call(java.util.function.Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            throw refused(e);
        }
    }

    private static Refused refused(RestClientResponseException e) {
        return new Refused(e.getStatusCode().value(), "Authentik refused the call (" + e.getStatusCode().value() + ")");
    }
}
