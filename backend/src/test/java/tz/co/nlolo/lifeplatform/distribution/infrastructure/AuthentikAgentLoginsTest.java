package tz.co.nlolo.lifeplatform.distribution.infrastructure;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.AuthentikClient;
import tz.co.nlolo.lifeplatform.AuthentikWorkspaceLogins;
import tz.co.nlolo.lifeplatform.FakeAuthentik;
import tz.co.nlolo.lifeplatform.distribution.api.InvalidAgentStateException;
import tz.co.nlolo.lifeplatform.distribution.application.AgentLogins;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Agent logins in Authentik (ticket 04 of the cut-over): the agent workspace on the person's one login. */
class AuthentikAgentLoginsTest {

    static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID PARTY = UUID.fromString("af5be302-593f-45e6-a0c3-4882be1e03aa");
    static final String USER = "b35143c7-37b6-4327-a7b9-dec5213cf557";
    static final String CREATED = "{\"pk\":35,\"username\":\"+255700000001\",\"is_active\":true,\"attributes\":{},"
        + "\"uuid\":\"" + USER + "\",\"groups_obj\":[]}";

    private final FakeAuthentik authentik = fake();
    private final AgentLogins logins = new AuthentikAgentLogins(
        new AuthentikWorkspaceLogins(new AuthentikClient(authentik.baseUrl(), "t")));

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
    void anAgentLoginJoinsTheAgentWorkspaceAndReturnsTheTokensSubject() {
        authentik.on("POST /api/v3/core/users/", 201, CREATED);
        authentik.on("GET /api/v3/core/groups/", 200, "{\"results\":[{\"pk\":\"g-agent\",\"name\":\"agent\"}]}");
        authentik.on("POST /api/v3/core/groups/g-agent/add_user/", 204, "");

        assertThat(logins.create("+255700000001", null, "Juma Senior", TENANT, PARTY)).isEqualTo(USER);
        assertThat(authentik.last("GET /api/v3/core/groups/").query()).contains("name=agent");
    }

    @Test
    void someoneElsesUsernameIsTheAgentsProblemToCorrect() {
        authentik.on("POST /api/v3/core/users/", 400, "{\"username\":[\"This field must be unique.\"]}");
        authentik.on("GET /api/v3/core/users/", 200, "{\"results\":[]}");

        assertThatThrownBy(() -> logins.create("+255700000001", null, "Juma Senior", TENANT, PARTY))
            .isInstanceOf(InvalidAgentStateException.class).hasMessageContaining("already exists for someone else");
    }

    @Test
    void aRefusalFromTheLoginServiceIsAnUnavailableServiceNotAnAgentProblem() {
        authentik.on("GET /api/v3/core/users/", 200, "{\"results\":[]}");

        assertThatThrownBy(() -> logins.setEnabled(USER, false)).isInstanceOf(IllegalStateException.class)
            .isNotInstanceOf(InvalidAgentStateException.class);
    }

    @Test
    void thereIsNoEmailedLink() {
        assertThatThrownBy(() -> logins.sendSetPasswordLink(USER)).isInstanceOf(InvalidAgentStateException.class);
        assertThat(authentik.requests()).isEmpty();
    }
}
