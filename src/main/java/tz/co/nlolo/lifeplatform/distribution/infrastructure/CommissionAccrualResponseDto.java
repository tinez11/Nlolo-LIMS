package tz.co.nlolo.lifeplatform.distribution.infrastructure;

import tz.co.nlolo.lifeplatform.distribution.api.CommissionAccrualView;
import tz.co.nlolo.lifeplatform.distribution.api.TierType;

import java.util.UUID;

/** {@code reversesAccrualId} is non-null exactly for a clawback row, and such a row's
 * {@code amount} is negative -- see {@link MoneyDto} on why this module's money DTO carries no
 * positivity constraint. */
public record CommissionAccrualResponseDto(UUID accrualId, UUID agentId, UUID statementId, String policyNumber,
                                            TierType tierType, MoneyDto amount, String period,
                                            String sourceRef, UUID reversesAccrualId) {

    public static CommissionAccrualResponseDto from(CommissionAccrualView view) {
        return new CommissionAccrualResponseDto(view.accrualId(), view.agentId(), view.statementId(),
            view.policyNumber(), view.tierType(), new MoneyDto(view.amount().toPlainString(), view.currency()),
            view.period(), view.sourceRef(), view.reversesAccrualId());
    }
}
