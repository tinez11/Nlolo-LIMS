package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import tz.co.nlolo.lifeplatform.finaccounting.api.AccountBalanceView;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountStatus;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountType;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.api.TrialBalanceView;

import java.util.List;

/**
 * The chart with its balances, on the wire.
 *
 * <p>Money is the platform's usual {@code {amount, currencyCode}} with a STRING amount, never a
 * JSON number -- a decimal that survives Postgres and Java exactly must not become a double in a
 * browser.
 *
 * <p>Unlike {@code GlPostingResponseDto}, these amounts are SIGNED where they are a balance:
 * {@code balance} is netted in the account's own normal direction, so a negative value is a real
 * anomaly (a debit-normal account in credit) rather than a rendering artefact. The DR and CR
 * totals beside it stay positive magnitudes, as everywhere else in this module.
 *
 * <p>{@code balanced} is stated by the server rather than left to a caller comparing two decimal
 * strings, which is the same rule that keeps every other total on this platform off the client.
 */
public record TrialBalanceResponseDto(String period, List<AccountBalanceResponseDto> accounts,
                                       MoneyDto totalDebit, MoneyDto totalCredit, boolean balanced) {

    /** One account's structure and its figures. `own*` are this account's own postings; the
     *  unprefixed pair rolls up every descendant. Both, so a summary account reporting millions
     *  it never received directly cannot be mistaken for one that did. */
    public record AccountBalanceResponseDto(String accountCode, String name, AccountType accountType,
                                             PostingDirection normalBalance, String parentCode,
                                             short level, boolean postingAllowed, AccountStatus status,
                                             MoneyDto ownDebit, MoneyDto ownCredit,
                                             MoneyDto debit, MoneyDto credit, MoneyDto balance) {

        static AccountBalanceResponseDto from(AccountBalanceView view) {
            String currency = view.currency();
            return new AccountBalanceResponseDto(view.accountCode(), view.name(), view.accountType(),
                view.normalBalance(), view.parentCode(), view.level(), view.postingAllowed(),
                view.status(),
                new MoneyDto(view.ownDebit().toPlainString(), currency),
                new MoneyDto(view.ownCredit().toPlainString(), currency),
                new MoneyDto(view.debit().toPlainString(), currency),
                new MoneyDto(view.credit().toPlainString(), currency),
                new MoneyDto(view.balance().toPlainString(), currency));
        }
    }

    public static TrialBalanceResponseDto from(TrialBalanceView view) {
        // The tenant's chart is single-currency by construction (every account carries the same
        // code), so the totals take it from the first account rather than inventing one. An empty
        // chart has no currency to state and no money to state it about.
        String currency = view.accounts().isEmpty() ? null : view.accounts().get(0).currency();
        return new TrialBalanceResponseDto(view.period(),
            view.accounts().stream().map(AccountBalanceResponseDto::from).toList(),
            new MoneyDto(view.totalDebit().toPlainString(), currency),
            new MoneyDto(view.totalCredit().toPlainString(), currency),
            view.balanced());
    }
}
