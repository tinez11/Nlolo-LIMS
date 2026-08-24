package tz.co.nlolo.lifeplatform.distribution.infrastructure;

import tz.co.nlolo.lifeplatform.distribution.api.CommissionPlanView;
import tz.co.nlolo.lifeplatform.distribution.api.PlanStatus;

import java.util.List;
import java.util.UUID;

public record CommissionPlanResponseDto(UUID planId, UUID productId, PlanStatus status, List<CommissionRuleDto> rules) {

    public static CommissionPlanResponseDto from(CommissionPlanView view) {
        return new CommissionPlanResponseDto(view.commissionPlanId(), view.productId(), view.status(),
            view.rules().stream().map(CommissionRuleDto::from).toList());
    }
}
