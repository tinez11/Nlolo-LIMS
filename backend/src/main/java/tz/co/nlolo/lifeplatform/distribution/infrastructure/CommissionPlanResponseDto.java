package tz.co.nlolo.lifeplatform.distribution.infrastructure;

import tz.co.nlolo.lifeplatform.distribution.api.CommissionPlanView;
import tz.co.nlolo.lifeplatform.distribution.api.PlanStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CommissionPlanResponseDto(UUID planId, UUID productId, PlanStatus status, List<CommissionRuleDto> rules,
                                        /* 2026-11-12, finding 190: the proposal and its decision. */
                                        String proposedBy, UUID proposedForAgentId, String decidedBy, String decisionReason,
                                        Instant proposedAt, String productName, String agentName) {

    public static CommissionPlanResponseDto from(CommissionPlanView view) {
        return new CommissionPlanResponseDto(view.commissionPlanId(), view.productId(), view.status(),
            view.rules().stream().map(CommissionRuleDto::from).toList(), view.proposedBy(), view.proposedForAgentId(),
            view.decidedBy(), view.decisionReason(), view.proposedAt(), view.productName(), view.agentName());
    }
}
