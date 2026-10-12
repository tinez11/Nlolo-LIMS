package tz.co.nlolo.lifeplatform.distribution.infrastructure;

import tz.co.nlolo.lifeplatform.distribution.api.AgentTeamMemberView;
import tz.co.nlolo.lifeplatform.distribution.api.LicenseStatus;

import java.util.UUID;

public record AgentTeamMemberDto(UUID agentId, String licenseNumber, LicenseStatus licenseStatus,
                                 UUID hierarchyParentId, String displayName) {

    public static AgentTeamMemberDto from(AgentTeamMemberView view) {
        return new AgentTeamMemberDto(view.agentId(), view.licenseNumber(), view.licenseStatus(),
            view.hierarchyParentId(), view.displayName());
    }
}
