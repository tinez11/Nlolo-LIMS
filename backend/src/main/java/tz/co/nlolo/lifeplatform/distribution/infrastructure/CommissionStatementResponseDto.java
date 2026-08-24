package tz.co.nlolo.lifeplatform.distribution.infrastructure;

import tz.co.nlolo.lifeplatform.distribution.api.CommissionStatementView;
import tz.co.nlolo.lifeplatform.distribution.api.StatementStatus;

import java.time.Instant;
import java.util.UUID;

public record CommissionStatementResponseDto(UUID statementId, UUID agentId, String period, MoneyDto totalAmount,
                                              StatementStatus status, Instant closedAt, Instant paidAt) {

    public static CommissionStatementResponseDto from(CommissionStatementView view) {
        return new CommissionStatementResponseDto(view.statementId(), view.agentId(), view.period(),
            new MoneyDto(view.totalAmount().toPlainString(), view.totalCurrency()),
            view.status(), view.closedAt(), view.paidAt());
    }
}
