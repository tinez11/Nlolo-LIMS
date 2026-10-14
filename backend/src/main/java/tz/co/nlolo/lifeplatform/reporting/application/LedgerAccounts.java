package tz.co.nlolo.lifeplatform.reporting.application;

import tz.co.nlolo.lifeplatform.finaccounting.api.AccountBalanceView;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The chart of accounts as the accounting reports read it.
 *
 * <p>The nine classes are the posting guide's (IFRS 17 Chart of Accounts and Posting Guide 2.5), seeded by
 * {@code ChartOfAccountBlueprint} from {@code finaccounting/ifrs17-chart.csv}. An account code is always four
 * digits whose first is its class, so a class is derivable without another round trip.
 */
final class LedgerAccounts {

    private LedgerAccounts() {}

    /** The nine class roots and their names, as the guide writes them. */
    static final Map<String, String> CLASS_NAMES = Map.ofEntries(
        Map.entry("1000", "Assets"),
        Map.entry("2000", "Liabilities"),
        Map.entry("3000", "Equity"),
        Map.entry("4000", "Insurance revenue (IFRS 17)"),
        Map.entry("5000", "Insurance service expenses (IFRS 17)"),
        Map.entry("6000", "Net result from reinsurance contracts held"),
        Map.entry("7000", "Finance and investment result"),
        Map.entry("8000", "Other operating expenses and tax"),
        Map.entry("9000", "Clearing and suspense"));

    /** What the entity owns, owes and is worth. A List, not a Set: a statement has an order. */
    static final List<String> BALANCE_SHEET_CLASSES = List.of("1000", "2000", "3000");

    /** What moved through the period, in the order a statement reads it. */
    static final List<String> INCOME_STATEMENT_CLASSES = List.of("4000", "5000", "6000", "7000", "8000");

    /** The result classes that ADD to the result rather than subtract from it. */
    private static final Set<String> INCOME_CLASSES = Set.of("4000", "7000");

    static String classOf(String accountCode) {
        return accountCode.charAt(0) + "000";
    }

    static List<AccountBalanceView> inClass(List<AccountBalanceView> accounts, String classRoot) {
        return accounts.stream().filter(a -> classRoot.equals(classOf(a.accountCode()))).toList();
    }

    /**
     * Whether anything reached this account. An account the ledger never touched is not a zero worth printing:
     * a 246-account chart would bury the dozen that moved under two hundred that did not.
     */
    static boolean posted(AccountBalanceView a) {
        return signum(a.ownDebit()) != 0 || signum(a.ownCredit()) != 0
            || signum(a.debit()) != 0 || signum(a.credit()) != 0;
    }

    /**
     * Whether a class adds to the result or subtracts from it.
     *
     * <p>Decided by the CLASS, never by sampling an account's {@code AccountType}: a class can be empty in a
     * period, and a contra account inside it keeps its parent's type with the opposite normal balance, so the
     * first account in a list is not a safe witness for the class it sits in.
     */
    static boolean isIncomeClass(String classRoot) {
        return INCOME_CLASSES.contains(classRoot);
    }

    private static int signum(BigDecimal value) {
        return value == null ? 0 : value.signum();
    }
}
