package tz.co.nlolo.lifeplatform.iam.infrastructure;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceTest {

    @Test
    void theHeaderPicksAWorkspaceTheTokenHolds() {
        assertThat(Workspace.resolve(List.of("agent", "customer"), "customer"))
            .isEqualTo(new Workspace.Resolution.Active(Workspace.CUSTOMER));
    }

    @Test
    void oneWorkspaceNeedsNoHeader() {
        assertThat(Workspace.resolve(List.of("staff", "ADMIN"), null))
            .isEqualTo(new Workspace.Resolution.Active(Workspace.STAFF));
    }

    @Test
    void aBlankHeaderCountsAsAbsent() {
        assertThat(Workspace.resolve(List.of("agent"), " "))
            .isEqualTo(new Workspace.Resolution.Active(Workspace.AGENT));
    }

    @Test
    void severalWorkspacesAndNoHeaderIsAmbiguous() {
        assertThat(Workspace.resolve(List.of("agent", "customer"), null))
            .isEqualTo(new Workspace.Resolution.Ambiguous(Set.of(Workspace.AGENT, Workspace.CUSTOMER)));
    }

    @Test
    void aWorkspaceTheTokenDoesNotHoldIsRefused() {
        assertThat(Workspace.resolve(List.of("customer"), "staff"))
            .isEqualTo(new Workspace.Resolution.NotHeld("staff"));
    }

    @Test
    void anUnknownHeaderValueIsRefused() {
        assertThat(Workspace.resolve(List.of("staff"), "ADMIN"))
            .isEqualTo(new Workspace.Resolution.NotHeld("ADMIN"));
    }

    @Test
    void noWorkspaceRoleAtAllIsNoWorkspace() {
        assertThat(Workspace.resolve(List.of("offline_access", "UNDERWRITER"), null))
            .isEqualTo(new Workspace.Resolution.NoWorkspace());
    }
}
