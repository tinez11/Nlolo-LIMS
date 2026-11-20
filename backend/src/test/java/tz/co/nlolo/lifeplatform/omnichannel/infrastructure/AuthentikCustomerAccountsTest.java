package tz.co.nlolo.lifeplatform.omnichannel.infrastructure;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.AuthentikClient;
import tz.co.nlolo.lifeplatform.AuthentikWorkspaceLogins;
import tz.co.nlolo.lifeplatform.FakeAuthentik;
import tz.co.nlolo.lifeplatform.omnichannel.api.PortalAccessRefusedException;
import tz.co.nlolo.lifeplatform.omnichannel.application.CustomerAccounts;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Customer portal logins in Authentik (ticket 04 of the cut-over): the customer workspace on the person's one login. */
class AuthentikCustomerAccountsTest {

    static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID PARTY = UUID.fromString("360c2569-aea6-48bf-86a5-f3d8b5ffb4b0");
    static final String USER = "b35143c7-37b6-4327-a7b9-dec5213cf557";
    static final String CREATED = "{\"pk\":35,\"username\":\"+255700000002\",\"is_active\":true,\"attributes\":{},"
        + "\"uuid\":\"" + USER + "\",\"groups_obj\":[]}";

    private final FakeAuthentik authentik = fake();
    private final CustomerAccounts accounts = new AuthentikCustomerAccounts(
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
    void anInviteJoinsTheCustomerWorkspaceAndReturnsTheTokensSubject() {
        authentik.on("POST /api/v3/core/users/", 201, CREATED);
        authentik.on("GET /api/v3/core/groups/", 200, "{\"results\":[{\"pk\":\"g-customer\",\"name\":\"customer\"}]}");
        authentik.on("POST /api/v3/core/groups/g-customer/add_user/", 204, "");

        assertThat(accounts.create("+255700000002", null, "Amina Owner", TENANT, PARTY)).isEqualTo(USER);
        assertThat(authentik.last("GET /api/v3/core/groups/").query()).contains("name=customer");
    }

    @Test
    void someoneElsesUsernameIsAConflict() {
        authentik.on("POST /api/v3/core/users/", 400, "{\"username\":[\"This field must be unique.\"]}");
        authentik.on("GET /api/v3/core/users/", 200, "{\"results\":[]}");

        assertThatThrownBy(() -> accounts.create("+255700000002", null, "Amina Owner", TENANT, PARTY))
            .isInstanceOf(PortalAccessRefusedException.class)
            .satisfies(e -> assertThat(((PortalAccessRefusedException) e).conflict()).isTrue());
    }

    @Test
    void aRefusalFromTheLoginServiceIsAnUnavailableService() {
        authentik.on("GET /api/v3/core/users/", 200, "{\"results\":[]}");

        assertThatThrownBy(() -> accounts.setTemporaryPassword(USER, "x")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void thereIsNoEmailedLink() {
        assertThatThrownBy(() -> accounts.sendSetPasswordLink(USER)).isInstanceOf(PortalAccessRefusedException.class);
        assertThat(authentik.requests()).isEmpty();
    }
}
