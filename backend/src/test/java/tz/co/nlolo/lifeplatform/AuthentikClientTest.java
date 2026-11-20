package tz.co.nlolo.lifeplatform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The shared Authentik API client's contract, against a stand-in answering as the dev Authentik 2026.8.5 does. */
class AuthentikClientTest {

    static final UUID UUID_1 = UUID.fromString("b35143c7-37b6-4327-a7b9-dec5213cf557");
    static final String USER_1 = "{\"pk\":35,\"username\":\"probe.one\",\"name\":\"Probe One\",\"is_active\":true,"
        + "\"attributes\":{\"tenant_id\":\"t-1\",\"home_branch\":\"DSM\",\"party_id\":\"p-1\"},\"path\":\"lifeplatform/staff\","
        + "\"type\":\"internal\",\"uuid\":\"b35143c7-37b6-4327-a7b9-dec5213cf557\"}";
    static final String PAGE_OF_USER_1 = "{\"pagination\":{\"count\":1},\"results\":[" + USER_1 + "]}";
    static final String EMPTY_PAGE = "{\"pagination\":{\"count\":0},\"results\":[]}";
    static final String STAFF_GROUPS = "{\"pagination\":{\"count\":1},\"results\":[{\"pk\":\"ddcc7c9a-f73b-4489-98b6-178f1c5a9b0e\","
        + "\"name\":\"staff\"}]}";

    private final FakeAuthentik authentik = newFake();
    private final AuthentikClient client = new AuthentikClient(authentik.baseUrl(), "backend-token");

    private static FakeAuthentik newFake() {
        try {
            return new FakeAuthentik();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterEach
    void stop() {
        authentik.close();
    }

    @Test
    void createsAUserAndReturnsBothItsIds() {
        authentik.on("POST /api/v3/core/users/", 201, USER_1);

        AuthentikClient.User user = client.createUser("probe.one", "Probe One", "lifeplatform/staff", null,
            Map.of("tenant_id", "t-1"));

        assertThat(user.pk()).isEqualTo(35);
        assertThat(user.uuid()).isEqualTo(UUID_1);
        FakeAuthentik.Request sent = authentik.last("POST /api/v3/core/users/");
        assertThat(sent.authorization()).isEqualTo("Bearer backend-token");
        assertThat(sent.body()).contains("\"username\":\"probe.one\"", "\"path\":\"lifeplatform/staff\"",
            "\"is_active\":true", "\"tenant_id\":\"t-1\"");
    }

    /**
     * 2026-11-19: Authentik drops a chunked request body -- every create answered 400 "This field is required." -- so the
     * body is sent whole, with its length. Plain HTTP/1.1 too: no cleartext HTTP/2 upgrade offered to a server that
     * doesn't speak it.
     */
    @Test
    void sendsTheBodyWholeOverPlainHttp11() {
        authentik.on("POST /api/v3/core/users/", 201, USER_1);
        authentik.on("PATCH /api/v3/core/users/35/", 200, USER_1);

        client.createUser("probe.one", "Probe One", "lifeplatform/staff", null, Map.of());
        client.setActive(35, false);

        for (String call : java.util.List.of("POST /api/v3/core/users/", "PATCH /api/v3/core/users/35/")) {
            FakeAuthentik.Request sent = authentik.last(call);
            assertThat(sent.transferEncoding()).as(call).isNull();
            assertThat(sent.upgrade()).as(call).isNull();
        }
    }

    @Test
    void aMissingUsernameIsNotMistakenForATakenOne() {
        authentik.on("POST /api/v3/core/users/", 400, "{\"username\":[\"This field is required.\"]}");

        assertThatThrownBy(() -> client.createUser("probe.one", "Probe One", "lifeplatform/staff", null, Map.of()))
            .isInstanceOf(AuthentikClient.Refused.class)
            .isNotInstanceOf(AuthentikClient.UsernameTaken.class);
    }

    @Test
    void aTakenUsernameIsItsOwnRefusalNotAnError() {
        authentik.on("POST /api/v3/core/users/", 400, "{\"username\":[\"This field must be unique.\"]}");

        assertThatThrownBy(() -> client.createUser("probe.one", "Probe One", "lifeplatform/staff", null, Map.of()))
            .isInstanceOf(AuthentikClient.UsernameTaken.class);
    }

    @Test
    void anotherBadRequestIsNotMistakenForATakenUsername() {
        authentik.on("POST /api/v3/core/users/", 400, "{\"path\":[\"Invalid path.\"]}");

        assertThatThrownBy(() -> client.createUser("probe.one", "Probe One", "bad//path", null, Map.of()))
            .isInstanceOf(AuthentikClient.Refused.class)
            .isNotInstanceOf(AuthentikClient.UsernameTaken.class)
            .satisfies(e -> assertThat(((AuthentikClient.Refused) e).status()).isEqualTo(400));
    }

    @Test
    void findsTheIntegerIdFromThePlatformsUuid() {
        authentik.on("GET /api/v3/core/users/", 200, PAGE_OF_USER_1);

        assertThat(client.user(UUID_1).pk()).isEqualTo(35);
        assertThat(authentik.last("GET /api/v3/core/users/").query()).contains("uuid=" + UUID_1);
    }

    @Test
    void findsALoginByUsernameWithTheWorkspacesItHolds() {
        authentik.on("GET /api/v3/core/users/", 200, "{\"pagination\":{\"count\":1},\"results\":[{\"pk\":35,"
            + "\"username\":\"+255700000001\",\"is_active\":true,\"attributes\":{},\"uuid\":\"" + UUID_1 + "\","
            + "\"groups_obj\":[{\"name\":\"agent\"},{\"name\":\"customer\"}]}]}");

        AuthentikClient.User user = client.findUserByUsername("+255700000001").orElseThrow();

        assertThat(user.groups()).containsExactly("agent", "customer");
        assertThat(authentik.last("GET /api/v3/core/users/").query()).contains("username=+255700000001");
    }

    @Test
    void aUserTheTokenCannotSeeIsNotFound() {
        authentik.on("GET /api/v3/core/users/", 200, EMPTY_PAGE);

        assertThat(client.findUser(UUID_1)).isEmpty();
        assertThatThrownBy(() -> client.user(UUID_1)).isInstanceOf(AuthentikClient.Refused.class)
            .satisfies(e -> assertThat(((AuthentikClient.Refused) e).status()).isEqualTo(404));
    }

    @Test
    void changingAttributesSavesTheWholeRecordSoNothingElseIsLost() {
        authentik.on("GET /api/v3/core/users/35/", 200, USER_1);
        authentik.on("PATCH /api/v3/core/users/35/", 200, USER_1);

        client.mergeAttributes(35, Map.of("reset_password", true));

        // A PATCH of attributes replaces the whole record: tenant_id, home_branch and party_id must ride along.
        assertThat(authentik.last("PATCH /api/v3/core/users/35/").body())
            .contains("\"tenant_id\":\"t-1\"", "\"home_branch\":\"DSM\"", "\"party_id\":\"p-1\"", "\"reset_password\":true");
    }

    @Test
    void addsAUserToAWorkspaceGroupByName() {
        authentik.on("GET /api/v3/core/groups/", 200, STAFF_GROUPS);
        authentik.on("POST /api/v3/core/groups/ddcc7c9a-f73b-4489-98b6-178f1c5a9b0e/add_user/", 204, "");

        client.addToGroup("staff", 35);

        assertThat(authentik.last("GET /api/v3/core/groups/").query()).contains("name=staff");
        assertThat(authentik.last("POST /api/v3/core/groups/ddcc7c9a-f73b-4489-98b6-178f1c5a9b0e/add_user/").body())
            .isEqualTo("{\"pk\":35}");
    }

    @Test
    void setsAPasswordAndTheActiveFlag() {
        authentik.on("POST /api/v3/core/users/35/set_password/", 204, "");
        authentik.on("PATCH /api/v3/core/users/35/", 200, USER_1);

        client.setPassword(35, "Temp-pass-123");
        client.setActive(35, false);

        assertThat(authentik.last("POST /api/v3/core/users/35/set_password/").body()).isEqualTo("{\"password\":\"Temp-pass-123\"}");
        assertThat(authentik.last("PATCH /api/v3/core/users/35/").body()).isEqualTo("{\"is_active\":false}");
    }

    /** 2026-11-19: Authentik's first call after a restart took over 10 seconds, and the timeout reached staff as a 500. */
    @Test
    void anAuthentikThatDoesNotAnswerIsARefusalNotAnUnexpectedError() {
        AuthentikClient nobodyHome = new AuthentikClient("http://127.0.0.1:1", "t");

        assertThatThrownBy(() -> nobodyHome.user(UUID_1)).isInstanceOf(AuthentikClient.Refused.class)
            .hasMessageContaining("did not answer")
            .satisfies(e -> assertThat(((AuthentikClient.Refused) e).status()).isEqualTo(503));
    }

    @Test
    void asksForARecoveryLinkThatLastsAsLongAsItIsTold() {
        authentik.on("POST /api/v3/core/users/35/recovery/", 200,
            "{\"link\":\"http://authentik-server:9000/if/flow/lifeplatform-set-password/?flow_token=abc\"}");

        assertThat(client.recoveryLink(35, "minutes=15"))
            .isEqualTo("http://authentik-server:9000/if/flow/lifeplatform-set-password/?flow_token=abc");
        assertThat(authentik.last("POST /api/v3/core/users/35/recovery/").body())
            .isEqualTo("{\"token_duration\":\"minutes=15\"}");
    }

    @Test
    void aRefusalCarriesItsStatus() {
        authentik.on("POST /api/v3/core/users/35/set_password/", 403, "{\"detail\":\"You do not have permission.\"}");

        assertThatThrownBy(() -> client.setPassword(35, "x")).isInstanceOf(AuthentikClient.Refused.class)
            .satisfies(e -> assertThat(((AuthentikClient.Refused) e).status()).isEqualTo(403));
    }
}
