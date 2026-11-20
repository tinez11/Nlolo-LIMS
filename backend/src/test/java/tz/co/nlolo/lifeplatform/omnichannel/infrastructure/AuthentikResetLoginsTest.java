package tz.co.nlolo.lifeplatform.omnichannel.infrastructure;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.AuthentikClient;
import tz.co.nlolo.lifeplatform.FakeAuthentik;
import tz.co.nlolo.lifeplatform.omnichannel.application.ResetLogins;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The logins /reset may reach, in Authentik (ticket 07 of the cut-over): agents' and customers', never staff's. */
class AuthentikResetLoginsTest {

    static final String PHONE = "+255713000101";
    static final UUID USER = UUID.fromString("69b0a421-bd24-49ff-93cd-6270f8fdb6b8");
    static final UUID PARTY = UUID.fromString("d158ac1e-1390-48dd-a5ca-d841480bee60");

    private final FakeAuthentik authentik = fake();
    private final ResetLogins logins = new AuthentikResetLogins(new AuthentikClient(authentik.baseUrl(), "t"),
        "https://login.example.tz");

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

    private void aLogin(boolean active, String party, String... groups) {
        StringBuilder obj = new StringBuilder();
        for (String g : groups) obj.append(obj.isEmpty() ? "" : ",").append("{\"name\":\"").append(g).append("\"}");
        authentik.on("GET /api/v3/core/users/", 200, "{\"results\":[{\"pk\":46,\"username\":\"" + PHONE + "\","
            + "\"is_active\":" + active + ",\"attributes\":{" + (party == null ? "" : "\"party_id\":\"" + party + "\"")
            + "},\"uuid\":\"" + USER + "\",\"groups_obj\":[" + obj + "]}]}");
    }

    @Test
    void anOpenCustomerOrAgentLoginIsFoundByItsPhone() {
        aLogin(true, PARTY.toString(), "customer");
        assertThat(logins.find(PHONE)).contains(new ResetLogins.ResetLogin(USER, PARTY));
        assertThat(authentik.last("GET /api/v3/core/users/").query()).contains("username=" + PHONE);
    }

    @Test
    void staffAClosedLoginOrOneWithNoPartyIsNeverOffered() {
        aLogin(true, PARTY.toString(), "staff");
        assertThat(logins.find(PHONE)).isEmpty();
    }

    @Test
    void aClosedLoginIsNotOffered() {
        aLogin(false, PARTY.toString(), "agent");
        assertThat(logins.find(PHONE)).isEmpty();
    }

    @Test
    void aLoginWithNoPartyOrNoWorkspaceIsNotOffered() {
        aLogin(true, null, "customer");
        assertThat(logins.find(PHONE)).isEmpty();
    }

    @Test
    void aLoginInStaffAndAnotherWorkspaceIsStillStaffAndNotOffered() {
        aLogin(true, PARTY.toString(), "agent", "staff");
        assertThat(logins.find(PHONE)).isEmpty();
    }

    /** The backend reaches Authentik on its internal address; the browser must follow the public one. */
    @Test
    void theRecoveryLinkLastsFifteenMinutesAndCarriesThePublicAddress() {
        aLogin(true, PARTY.toString(), "customer");
        authentik.on("POST /api/v3/core/users/46/recovery/", 200,
            "{\"link\":\"http://authentik-server:9000/if/flow/lifeplatform-set-password/?flow_token=abc\"}");

        assertThat(logins.recoveryLink(USER))
            .isEqualTo("https://login.example.tz/if/flow/lifeplatform-set-password/?flow_token=abc");
        assertThat(authentik.last("POST /api/v3/core/users/46/recovery/").body()).contains("minutes=15");
    }
}
