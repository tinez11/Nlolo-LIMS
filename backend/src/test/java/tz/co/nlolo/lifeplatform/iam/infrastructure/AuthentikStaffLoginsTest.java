package tz.co.nlolo.lifeplatform.iam.infrastructure;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.AuthentikClient;
import tz.co.nlolo.lifeplatform.FakeAuthentik;
import tz.co.nlolo.lifeplatform.iam.api.AccessChangeRefusedException;
import tz.co.nlolo.lifeplatform.iam.application.StaffLogins;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tz.co.nlolo.lifeplatform.iam.api.AccessChangeRefusedException.Reason.LOGIN_PROVIDER;
import static tz.co.nlolo.lifeplatform.iam.api.AccessChangeRefusedException.Reason.USERNAME_TAKEN;

/** Staff logins through Authentik's API (ticket 03 of the cut-over): the Users screen's four operations. */
class AuthentikStaffLoginsTest {

    static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID USER = UUID.fromString("b35143c7-37b6-4327-a7b9-dec5213cf557");
    static final String CREATED = "{\"pk\":35,\"username\":\"asha\",\"is_active\":true,\"attributes\":{\"tenant_id\":\""
        + TENANT + "\",\"home_branch\":\"DSM\",\"reset_password\":true},\"uuid\":\"" + USER + "\"}";
    static final String PAGE = "{\"pagination\":{\"count\":1},\"results\":[" + CREATED + "]}";
    static final String STAFF = "{\"pagination\":{\"count\":1},\"results\":[{\"pk\":\"g-staff\",\"name\":\"staff\"}]}";

    private final FakeAuthentik authentik = fake();
    private final StaffLogins logins = new AuthentikStaffLogins(new AuthentikClient(authentik.baseUrl(), "t"));

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

    private static StaffLogins.NewLogin asha() {
        return new StaffLogins.NewLogin("asha", "Asha", "Mrema", null, TENANT, "DSM");
    }

    @Test
    void aNewStaffLoginHoldsItsTenantAndBranchJoinsStaffAndMustChooseAPassword() {
        authentik.on("POST /api/v3/core/users/", 201, CREATED);
        authentik.on("GET /api/v3/core/groups/", 200, STAFF);
        authentik.on("POST /api/v3/core/groups/g-staff/add_user/", 204, "");

        UUID id = logins.create(asha());

        assertThat(id).isEqualTo(USER);
        assertThat(authentik.last("POST /api/v3/core/users/").body()).contains("\"username\":\"asha\"",
            "\"name\":\"Asha Mrema\"", "\"tenant_id\":\"" + TENANT + "\"", "\"home_branch\":\"DSM\"",
            "\"reset_password\":true", "\"path\":\"lifeplatform/staff\"");
        assertThat(authentik.writes()).containsExactly("POST /api/v3/core/users/", "POST /api/v3/core/groups/g-staff/add_user/");
    }

    @Test
    void aTakenUsernameIsRefusedAsTaken() {
        authentik.on("POST /api/v3/core/users/", 400, "{\"username\":[\"This field must be unique.\"]}");

        assertThatThrownBy(() -> logins.create(asha())).isInstanceOf(AccessChangeRefusedException.class)
            .satisfies(e -> assertThat(((AccessChangeRefusedException) e).reason()).isEqualTo(USERNAME_TAKEN));
    }

    @Test
    void aLoginThatCouldNotJoinStaffIsRemovedSoARetryIsNotTakenByIt() {
        authentik.on("POST /api/v3/core/users/", 201, CREATED);
        authentik.on("GET /api/v3/core/groups/", 200, STAFF);
        authentik.on("POST /api/v3/core/groups/g-staff/add_user/", 403, "{\"detail\":\"denied\"}");
        authentik.on("DELETE /api/v3/core/users/35/", 204, "");

        assertThatThrownBy(() -> logins.create(asha())).isInstanceOf(AccessChangeRefusedException.class)
            .satisfies(e -> assertThat(((AccessChangeRefusedException) e).reason()).isEqualTo(LOGIN_PROVIDER));
        assertThat(authentik.writes()).endsWith("DELETE /api/v3/core/users/35/");
    }

    @Test
    void aTemporaryPasswordIsSetThenFlaggedForChangeKeepingTheOtherAttributes() {
        authentik.on("GET /api/v3/core/users/", 200, PAGE);
        authentik.on("POST /api/v3/core/users/35/set_password/", 204, "");
        authentik.on("GET /api/v3/core/users/35/", 200, CREATED);
        authentik.on("PATCH /api/v3/core/users/35/", 200, CREATED);

        logins.setTemporaryPassword(USER, "Temp-pass-123");

        assertThat(authentik.writes()).containsExactly("POST /api/v3/core/users/35/set_password/", "PATCH /api/v3/core/users/35/");
        assertThat(authentik.last("PATCH /api/v3/core/users/35/").body())
            .contains("\"reset_password\":true", "\"tenant_id\":\"" + TENANT + "\"", "\"home_branch\":\"DSM\"");
    }

    @Test
    void closingALoginDisablesIt() {
        authentik.on("GET /api/v3/core/users/", 200, PAGE);
        authentik.on("PATCH /api/v3/core/users/35/", 200, CREATED);

        logins.setEnabled(USER, false);

        assertThat(authentik.last("PATCH /api/v3/core/users/35/").body()).isEqualTo("{\"is_active\":false}");
    }

    @Test
    void aLoginTheBackendDidNotCreateIsRefusedWithoutChangingAnything() {
        authentik.on("GET /api/v3/core/users/", 200, "{\"pagination\":{\"count\":0},\"results\":[]}");

        assertThatThrownBy(() -> logins.setTemporaryPassword(USER, "x")).isInstanceOf(AccessChangeRefusedException.class)
            .satisfies(e -> assertThat(((AccessChangeRefusedException) e).reason()).isEqualTo(LOGIN_PROVIDER));
        assertThat(authentik.writes()).isEmpty();
    }

    @Test
    void anEmailedLinkIsNotOfferedSinceEmailIsNotAChannel() {
        assertThatThrownBy(() -> logins.sendSetPasswordLink(USER)).isInstanceOf(AccessChangeRefusedException.class);
        assertThat(authentik.requests()).isEmpty();
    }
}
