package tz.co.nlolo.lifeplatform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One login per person across the agent and customer workspaces (ticket 04 of the Authentik cut-over): granting joins
 * the person's existing login, revoking takes only that workspace away, and a login with no workspace left is closed.
 */
class AuthentikWorkspaceLoginsTest {

    static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID PARTY = UUID.fromString("af5be302-593f-45e6-a0c3-4882be1e03aa");
    static final UUID USER = UUID.fromString("b35143c7-37b6-4327-a7b9-dec5213cf557");
    static final String PHONE = "+255700000001";

    static String user(boolean active, String party, String... groups) {
        StringBuilder obj = new StringBuilder();
        for (String g : groups) obj.append(obj.isEmpty() ? "" : ",").append("{\"name\":\"").append(g).append("\"}");
        return "{\"pk\":35,\"username\":\"" + PHONE + "\",\"is_active\":" + active + ",\"attributes\":{\"tenant_id\":\""
            + TENANT + "\",\"party_id\":\"" + party + "\"},\"uuid\":\"" + USER + "\",\"groups_obj\":[" + obj + "]}";
    }

    static String page(String user) {
        return "{\"pagination\":{\"count\":1},\"results\":[" + user + "]}";
    }

    static String groups(String name) {
        return "{\"pagination\":{\"count\":1},\"results\":[{\"pk\":\"g-" + name + "\",\"name\":\"" + name + "\"}]}";
    }

    private final FakeAuthentik authentik = fake();
    private final AuthentikWorkspaceLogins logins = new AuthentikWorkspaceLogins(new AuthentikClient(authentik.baseUrl(), "t"));

    private static FakeAuthentik fake() {
        try {
            return new FakeAuthentik();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterEach
    void stop() {
        authentik.close();
    }

    @Test
    void aNewPersonGetsANewLoginInTheWorkspaceCarryingTenantAndParty() {
        authentik.on("POST /api/v3/core/users/", 201, user(true, PARTY.toString()));
        authentik.on("GET /api/v3/core/groups/", 200, groups("agent"));
        authentik.on("POST /api/v3/core/groups/g-agent/add_user/", 204, "");

        UUID id = logins.grant("agent", PHONE, null, "Juma Senior", TENANT, PARTY);

        assertThat(id).isEqualTo(USER);
        assertThat(authentik.last("POST /api/v3/core/users/").body()).contains("\"username\":\"" + PHONE + "\"",
            "\"tenant_id\":\"" + TENANT + "\"", "\"party_id\":\"" + PARTY + "\"", "\"reset_password\":true");
        assertThat(authentik.writes()).containsExactly("POST /api/v3/core/users/", "POST /api/v3/core/groups/g-agent/add_user/");
    }

    @Test
    void thePersonsExistingLoginIsJoinedNotDuplicated() {
        authentik.on("POST /api/v3/core/users/", 400, "{\"username\":[\"This field must be unique.\"]}");
        authentik.on("GET /api/v3/core/users/", 200, page(user(true, PARTY.toString(), "agent")));
        authentik.on("GET /api/v3/core/groups/", 200, groups("customer"));
        authentik.on("POST /api/v3/core/groups/g-customer/add_user/", 204, "");

        UUID id = logins.grant("customer", PHONE, null, "Juma Senior", TENANT, PARTY);

        assertThat(id).isEqualTo(USER);
        assertThat(authentik.writes()).containsExactly("POST /api/v3/core/users/",
            "POST /api/v3/core/groups/g-customer/add_user/");
    }

    @Test
    void aClosedLoginOfTheSamePersonIsReopenedWhenJoined() {
        authentik.on("POST /api/v3/core/users/", 400, "{\"username\":[\"This field must be unique.\"]}");
        authentik.on("GET /api/v3/core/users/", 200, page(user(false, PARTY.toString())));
        authentik.on("GET /api/v3/core/groups/", 200, groups("customer"));
        authentik.on("POST /api/v3/core/groups/g-customer/add_user/", 204, "");
        authentik.on("PATCH /api/v3/core/users/35/", 200, user(true, PARTY.toString(), "customer"));

        logins.grant("customer", PHONE, null, "Juma Senior", TENANT, PARTY);

        assertThat(authentik.writes()).endsWith("PATCH /api/v3/core/users/35/");
        assertThat(authentik.last("PATCH /api/v3/core/users/35/").body()).isEqualTo("{\"is_active\":true}");
    }

    @Test
    void someoneElsesLoginIsNeverTakenOver() {
        authentik.on("POST /api/v3/core/users/", 400, "{\"username\":[\"This field must be unique.\"]}");
        authentik.on("GET /api/v3/core/users/", 200, page(user(true, UUID.randomUUID().toString(), "customer")));

        assertThatThrownBy(() -> logins.grant("agent", PHONE, null, "Juma Senior", TENANT, PARTY))
            .isInstanceOf(AuthentikWorkspaceLogins.TakenBySomeoneElse.class);
        assertThat(authentik.writes()).containsExactly("POST /api/v3/core/users/");
    }

    @Test
    void aTakenUsernameTheServiceCannotSeeIsSomeoneElses() {
        // A staff, dev or server-made login: not one this service created, so not visible to it.
        authentik.on("POST /api/v3/core/users/", 400, "{\"username\":[\"This field must be unique.\"]}");
        authentik.on("GET /api/v3/core/users/", 200, "{\"pagination\":{\"count\":0},\"results\":[]}");

        assertThatThrownBy(() -> logins.grant("agent", PHONE, null, "Juma Senior", TENANT, PARTY))
            .isInstanceOf(AuthentikWorkspaceLogins.TakenBySomeoneElse.class);
    }

    @Test
    void aNewLoginThatCouldNotJoinItsWorkspaceIsRemoved() {
        authentik.on("POST /api/v3/core/users/", 201, user(true, PARTY.toString()));
        authentik.on("GET /api/v3/core/groups/", 200, groups("agent"));
        authentik.on("POST /api/v3/core/groups/g-agent/add_user/", 403, "{\"detail\":\"denied\"}");
        authentik.on("DELETE /api/v3/core/users/35/", 204, "");

        assertThatThrownBy(() -> logins.grant("agent", PHONE, null, "Juma Senior", TENANT, PARTY))
            .isInstanceOf(AuthentikClient.Refused.class);
        assertThat(authentik.writes()).endsWith("DELETE /api/v3/core/users/35/");
    }

    @Test
    void revokingOneOfTwoWorkspacesKeepsTheLoginButEndsItsSessions() {
        authentik.on("GET /api/v3/core/users/", 200, page(user(true, PARTY.toString(), "agent", "customer")),
            page(user(true, PARTY.toString(), "customer")));
        authentik.on("GET /api/v3/core/groups/", 200, groups("agent"));
        authentik.on("POST /api/v3/core/groups/g-agent/remove_user/", 204, "");
        authentik.on("PATCH /api/v3/core/users/35/", 200, user(true, PARTY.toString(), "customer"));

        logins.revoke(USER, "agent");

        // Deactivating ends every session and refresh token; reactivating keeps the portal open.
        assertThat(authentik.writes()).containsExactly("POST /api/v3/core/groups/g-agent/remove_user/",
            "PATCH /api/v3/core/users/35/", "PATCH /api/v3/core/users/35/");
        assertThat(authentik.last("PATCH /api/v3/core/users/35/").body()).isEqualTo("{\"is_active\":true}");
    }

    @Test
    void revokingTheLastWorkspaceClosesTheLogin() {
        authentik.on("GET /api/v3/core/users/", 200, page(user(true, PARTY.toString(), "customer")),
            page(user(true, PARTY.toString())));
        authentik.on("GET /api/v3/core/groups/", 200, groups("customer"));
        authentik.on("POST /api/v3/core/groups/g-customer/remove_user/", 204, "");
        authentik.on("PATCH /api/v3/core/users/35/", 200, user(false, PARTY.toString()));

        logins.revoke(USER, "customer");

        assertThat(authentik.writes()).containsExactly("POST /api/v3/core/groups/g-customer/remove_user/",
            "PATCH /api/v3/core/users/35/");
        assertThat(authentik.last("PATCH /api/v3/core/users/35/").body()).isEqualTo("{\"is_active\":false}");
    }

    @Test
    void reopeningAWorkspaceAddsItAndOpensTheLogin() {
        authentik.on("GET /api/v3/core/users/", 200, page(user(false, PARTY.toString())));
        authentik.on("GET /api/v3/core/groups/", 200, groups("agent"));
        authentik.on("POST /api/v3/core/groups/g-agent/add_user/", 204, "");
        authentik.on("PATCH /api/v3/core/users/35/", 200, user(true, PARTY.toString(), "agent"));

        logins.restore(USER, "agent");

        assertThat(authentik.writes()).containsExactly("POST /api/v3/core/groups/g-agent/add_user/",
            "PATCH /api/v3/core/users/35/");
        assertThat(authentik.last("PATCH /api/v3/core/users/35/").body()).isEqualTo("{\"is_active\":true}");
    }

    @Test
    void aTemporaryPasswordIsFlaggedForChangeKeepingTheParty() {
        authentik.on("GET /api/v3/core/users/", 200, page(user(true, PARTY.toString(), "agent")));
        authentik.on("POST /api/v3/core/users/35/set_password/", 204, "");
        authentik.on("GET /api/v3/core/users/35/", 200, user(true, PARTY.toString(), "agent"));
        authentik.on("PATCH /api/v3/core/users/35/", 200, user(true, PARTY.toString(), "agent"));

        logins.setTemporaryPassword(USER, "Temp-pass-123");

        assertThat(authentik.last("PATCH /api/v3/core/users/35/").body())
            .contains("\"reset_password\":true", "\"party_id\":\"" + PARTY + "\"", "\"tenant_id\":\"" + TENANT + "\"");
    }
}
