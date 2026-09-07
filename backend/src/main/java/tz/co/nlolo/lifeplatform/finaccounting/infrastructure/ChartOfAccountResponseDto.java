package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import tz.co.nlolo.lifeplatform.finaccounting.api.AccountStatus;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountType;
import tz.co.nlolo.lifeplatform.finaccounting.api.ChartOfAccountView;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;

import java.time.Instant;

/** One row of {@code finaccounting.chart_of_account}. Every account on this platform is currently
 * a PLACEHOLDER pending Finance sign-off (see {@code ChartOfAccountView}'s javadoc). No money
 * field: a chart-of-account row still carries no balance of its own -- account balances and a
 * trial balance are a separate, not-yet-built concern. */
public record ChartOfAccountResponseDto(String accountCode, String name, AccountType accountType,
                                         PostingDirection normalBalance, String parentCode,
                                         short level, boolean postingAllowed, AccountStatus status,
                                         String currency, String controlOf, String description,
                                         Instant createdAt, String createdBy) {

    public static ChartOfAccountResponseDto from(ChartOfAccountView view) {
        return new ChartOfAccountResponseDto(view.accountCode(), view.name(), view.accountType(),
            view.normalBalance(), view.parentCode(), view.level(), view.postingAllowed(),
            view.status(), view.currency(), view.controlOf(), view.description(),
            view.createdAt(), view.createdBy());
    }
}
