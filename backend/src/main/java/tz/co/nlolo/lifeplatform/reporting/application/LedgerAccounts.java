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
     * The accounts in this class that were actually posted to.
     *
     * <p>{@code postingAllowed} is the load-bearing half. A class is a hierarchy -- 1000 Assets, 1100 Cash and
     * bank, 1140 the wallet that takes the posting -- and a summary account's {@code balance} already contains
     * everything beneath it. Listing summaries beside their own leaves, and adding the column up, reports one
     * posting of 500 as 1,500 in assets: a sound ledger rendered as a wild overstatement. So a statement lists
     * the accounts that take postings, and leaves the aggregating to {@link #classTotal}.
     */
    static List<AccountBalanceView> postedLeavesIn(List<AccountBalanceView> accounts, String classRoot) {
        return inClass(accounts, classRoot).stream()
            .filter(AccountBalanceView::postingAllowed)
            .filter(LedgerAccounts::posted)
            .toList();
    }

    /**
     * What a whole class comes to: the class root's ROLLED balance, which is the ledger's own aggregation and
     * the reason the chart is a hierarchy at all. It nets raw debits and credits in the root's normal
     * direction, so a contra account beneath it is handled correctly -- which summing the leaves' own signed
     * balances would not guarantee.
     *
     * <p>Falls back to the leaves only when the chart has no root for the class, which a seeded chart always
     * does; the fallback exists so a hand-authored chart degrades to a defensible number rather than zero.
     */
    static BigDecimal classTotal(List<AccountBalanceView> accounts, String classRoot) {
        return accounts.stream()
            .filter(a -> a.accountCode().equals(classRoot))
            .map(AccountBalanceView::balance)
            .findFirst()
            .orElseGet(() -> postedLeavesIn(accounts, classRoot).stream()
                .map(AccountBalanceView::balance)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
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
