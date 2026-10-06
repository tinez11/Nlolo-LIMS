package tz.co.nlolo.lifeplatform.distribution.infrastructure;

import tz.co.nlolo.lifeplatform.distribution.api.AgentView;
import tz.co.nlolo.lifeplatform.distribution.api.LicenseStatus;
import tz.co.nlolo.lifeplatform.distribution.api.SalesChannel;

import java.time.LocalDate;
import java.util.UUID;

public record AgentResponseDto(UUID agentId, UUID partyId, String licenseNumber, LicenseStatus licenseStatus,
                                LocalDate licenseExpiryDate, UUID hierarchyParentId, UUID commissionPlanId,
                                SalesChannel salesChannel, String homeBranch) {

    public static AgentResponseDto from(AgentView view) {
        return new AgentResponseDto(view.agentId(), view.partyId(), view.licenseNumber(), view.licenseStatus(),
            view.licenseExpiryDate(), view.hierarchyParentId(), view.commissionPlanId(),
            view.salesChannel(), view.homeBranch());
    }
}
