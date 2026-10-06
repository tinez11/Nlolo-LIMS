package tz.co.nlolo.lifeplatform.finaccounting.domain;

import tz.co.nlolo.lifeplatform.finaccounting.api.AccountType;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;

/**
 * The guide's account classes (2.1) by leading digit: 1 assets, 2 liabilities, 3 equity, 4 insurance revenue,
 * 5 insurance service expenses, 6 net result from reinsurance held, 7 finance and investment result, 8 other operating
 * expenses and tax, 9 clearing. Used ONLY for a class root created through the API: the seeded chart carries every
 * account's type explicitly, and a hand-added child inherits its parent's (IFRS 17 I1, R4). Which accounts an event
 * posts to is the posting rules' business ({@code finaccounting/posting-rules.yaml}), not this class's.
 */
public final class AccountClasses {

    private AccountClasses() {}

    /** The class's usual balance: LIABILITY/EQUITY/INCOME credit; ASSET/EXPENSE/CLEARING debit. */
    public static PostingDirection normalBalanceFor(String accountCode) {
        return switch (accountTypeFor(accountCode)) {
            case LIABILITY, EQUITY, INCOME -> PostingDirection.CR;
            default -> PostingDirection.DR;
        };
    }

    public static AccountType accountTypeFor(String accountCode) {
        return switch (accountCode.charAt(0)) {
            case '1' -> AccountType.ASSET;
            case '2' -> AccountType.LIABILITY;
            case '3' -> AccountType.EQUITY;
            case '4', '7' -> AccountType.INCOME;
            case '5', '6', '8' -> AccountType.EXPENSE;
            case '9' -> AccountType.CLEARING;
            default -> throw new IllegalArgumentException("Unrecognised account code block: " + accountCode);
        };
    }
}
