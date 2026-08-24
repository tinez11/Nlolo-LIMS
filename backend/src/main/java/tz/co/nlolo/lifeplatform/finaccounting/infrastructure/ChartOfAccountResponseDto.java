package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import tz.co.nlolo.lifeplatform.finaccounting.api.AccountType;
import tz.co.nlolo.lifeplatform.finaccounting.api.ChartOfAccountView;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;

/** One row of {@code finaccounting.chart_of_account}. Every account on this platform is currently
 * a PLACEHOLDER pending Finance sign-off (see {@code ChartOfAccountView}'s javadoc). No money
 * field: a chart-of-account row carries no balance of its own in M9. */
public record ChartOfAccountResponseDto(String accountCode, String name, AccountType accountType,
                                         PostingDirection normalBalance) {

    public static ChartOfAccountResponseDto from(ChartOfAccountView view) {
        return new ChartOfAccountResponseDto(view.accountCode(), view.name(),
            view.accountType(), view.normalBalance());
    }
}
