package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import tz.co.nlolo.lifeplatform.finaccounting.api.AccountStatus;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountType;
import tz.co.nlolo.lifeplatform.finaccounting.api.ChartOfAccountView;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingMode;

import java.time.Instant;

/** One row of {@code finaccounting.chart_of_account}, with the IFRS 17 posting guide's posting {@code mode}.
 *
 * <p>No money field, and that is now a SPLIT rather than a gap: balances live on
 * {@code GET /chart-of-accounts/balances} ({@code TrialBalanceResponseDto}), so a caller reading
 * the chart to render a tree or fill a picker does not pay for an aggregation over the whole
 * posting table. This javadoc used to say a trial balance was "a separate, not-yet-built
 * concern"; it is built. */
public record ChartOfAccountResponseDto(String accountCode, String name, AccountType accountType,
                                         PostingDirection normalBalance, String parentCode,
                                         short level, boolean postingAllowed, AccountStatus status,
                                         String currency, String controlOf, String description,
                                         Instant createdAt, String createdBy, PostingMode mode) {

    public static ChartOfAccountResponseDto from(ChartOfAccountView view) {
        return new ChartOfAccountResponseDto(view.accountCode(), view.name(), view.accountType(),
            view.normalBalance(), view.parentCode(), view.level(), view.postingAllowed(),
            view.status(), view.currency(), view.controlOf(), view.description(),
            view.createdAt(), view.createdBy(), view.mode());
    }
}
