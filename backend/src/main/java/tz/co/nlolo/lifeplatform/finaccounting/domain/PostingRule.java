package tz.co.nlolo.lifeplatform.finaccounting.domain;

import tz.co.nlolo.lifeplatform.finaccounting.api.AccountType;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;

import java.util.Map;
import java.util.Optional;

/**
 * <b>INTERIM (IFRS 17 I1, correction R2): today's single-pair rules, re-pointed onto the IFRS 17 posting guide's chart.</b>
 * I1 replaced the placeholder chart with the guide's, which removed or changed the meaning of every code these rules
 * posted to (1210, 2140, 2150, 4310, 5100, 5600 ... and 2110, now the LRC's present value). Each constant below now
 * names the nearest guide account an EVENT journal may post to (AUTO or BOTH; the database's mode guard refuses the
 * rest). I3 replaces this whole class with the versioned rules file and the guide's full entries (A-K), splitting
 * what these single pairs still merge:
 * <ul>
 *   <li>{@code CLAIMS_EXPENSE} is 5110 Incurred claims - death for every benefit until I3 splits it by benefit and
 *       separates the investment component (2124);</li>
 *   <li>{@code CASH} is 1140 Mobile money wallets for money in and out (every money path is mobile money today);</li>
 *   <li>{@code reinsurance.CessionRecorded} still posts the ceded SUM ASSURED, a known bug I3 fixes on the bordereau
 *       (guide K-01).</li>
 * </ul>
 *
 * <p>The ledger is an accrual ledger: an invoice raises 2122 Premiums due against 2121 Premiums and other contract
 * inflows, and a collection settles 2122 against cash. Neither touches revenue: under IFRS 17 revenue comes only from
 * the period-end measurement (or PAA earning), not from premium.
 */
public final class PostingRule {

    private PostingRule() {}

    /** One balanced pair: which account is debited, which is credited. */
    public record AccountPair(String debitAccount, String creditAccount) {}

    public static final String CASH = "1140";
    public static final String PREMIUM_RECEIVABLE = "2122";
    public static final String REINSURANCE_RECOVERABLE = "1420";
    public static final String POLICY_LOAN_RECEIVABLE = "2125";
    public static final String CLAIMS_PAYABLE = "2211";
    public static final String UNEARNED_PREMIUM = "2121";
    /** Product step 6: what is owed in units -- 2131 Unit fund value. */
    public static final String UNIT_LINKED_LIABILITY = "2131";
    public static final String POLICYHOLDER_BENEFITS_PAYABLE = "2213";
    public static final String OTHER_RECEIVABLES = "2122";
    public static final String UNIT_LINKED_CHARGES_INCOME = "2132";
    public static final String CHANGE_IN_UNIT_LINKED_LIABILITY = "7130";
    public static final String REINSURANCE_PAYABLE = "1430";
    /** Tax withheld from a payout, owed to the authority (product step 5). */
    public static final String WITHHOLDING_TAX_PAYABLE = "2615";
    public static final String CLAIMS_EXPENSE = "5110";
    public static final String COMMISSION_EXPENSE = "2123";
    public static final String REINSURANCE_CEDED_PREMIUM = "1436";

    private static final Map<String, AccountPair> RULES = Map.ofEntries(
        Map.entry("billing.PremiumInvoiceGenerated", new AccountPair(PREMIUM_RECEIVABLE, UNEARNED_PREMIUM)),
        Map.entry("billing.PremiumCollected",        new AccountPair(CASH, PREMIUM_RECEIVABLE)),
        // A premium credit is the invoice posting run backwards, for the part given back. It had
        // no rule at all: billing recorded 4,200 credited to a lender while the ledger went on
        // carrying the whole 13,800 as receivable -- the two could not be reconciled.
        Map.entry("billing.PremiumRefundDue",        new AccountPair(UNEARNED_PREMIUM, PREMIUM_RECEIVABLE)),
        // An untouched instalment restated in place (family funeral cover): only the difference moves, the
        // way the invoice was first posted (up) or run backwards (down). Not PremiumRefundDue, which
        // distribution claws commission back on -- nothing was collected here, so nothing was earned.
        Map.entry("billing.PremiumInvoiceIncreased", new AccountPair(PREMIUM_RECEIVABLE, UNEARNED_PREMIUM)),
        Map.entry("billing.PremiumInvoiceReduced",   new AccountPair(UNEARNED_PREMIUM, PREMIUM_RECEIVABLE)),
        // A waived invoice, for what was still outstanding on it: the invoice posting run backwards. A finance
        // write-off, a vesting, or a policy that ended. It had no rule, so every waiver left its receivable behind.
        Map.entry("billing.InvoiceWaived",           new AccountPair(UNEARNED_PREMIUM, PREMIUM_RECEIVABLE)),
        Map.entry("claims.ClaimSettled",             new AccountPair(CLAIMS_EXPENSE, CASH)),
        // A surrender value paid out. Against Claims Expense because the chart has no surrender-benefit
        // account and adding one reopens the V5 chart remap -- a placeholder pending FINANCE sign-off,
        // like every rule here. Before this rule the money left and the ledger never saw it.
        Map.entry("policy.SurrenderPaid",            new AccountPair(CLAIMS_EXPENSE, CASH)),
        // A benefit paid while the life assured LIVES -- a maturity, a survival benefit, an income
        // instalment, a premium return. Against Claims Expense for the same reason a surrender is:
        // the chart has no benefits-paid account, and adding one reopens the V5 chart remap. A
        // placeholder pending FINANCE sign-off, like every rule in this file.
        Map.entry("benefitpayout.PayoutPaid",        new AccountPair(CLAIMS_EXPENSE, CASH)),
        Map.entry("distribution.CommissionPaid",     new AccountPair(COMMISSION_EXPENSE, CASH)),
        Map.entry("reinsurance.CessionRecorded",     new AccountPair(REINSURANCE_CEDED_PREMIUM, REINSURANCE_PAYABLE)),
        Map.entry("reinsurance.RecoveryConfirmed",   new AccountPair(REINSURANCE_RECOVERABLE, CLAIMS_EXPENSE)),
        Map.entry("policyloan.LoanDisbursed",        new AccountPair(POLICY_LOAN_RECEIVABLE, CASH)),
        Map.entry("policyloan.LoanRepaid",           new AccountPair(CASH, POLICY_LOAN_RECEIVABLE)),
        Map.entry("payment.EftDisbursementAwaitingExecution", new AccountPair(CLAIMS_EXPENSE, CLAIMS_PAYABLE)),
        Map.entry("payment.EftDisbursementExecuted",  new AccountPair(CLAIMS_PAYABLE, CLAIMS_EXPENSE)));

    /** Empty for any event with no accounting consequence -- which is most events on this
     * platform, and is a normal outcome rather than an error. */
    public static Optional<AccountPair> forEvent(String eventType) {
        return Optional.ofNullable(RULES.get(eventType));
    }

    /** The class's usual balance: LIABILITY/EQUITY/INCOME credit; ASSET/EXPENSE/CLEARING debit. */
    public static PostingDirection normalBalanceFor(String accountCode) {
        return switch (accountTypeFor(accountCode)) {
            case LIABILITY, EQUITY, INCOME -> PostingDirection.CR;
            default -> PostingDirection.DR;
        };
    }

    /**
     * The guide's account classes (2.1) by leading digit: 1 assets, 2 liabilities, 3 equity, 4 insurance revenue,
     * 5 insurance service expenses, 6 net result from reinsurance held, 7 finance and investment result, 8 other
     * operating expenses and tax, 9 clearing. Used ONLY for a class root created through the API: the seeded chart
     * carries every account's type explicitly, and a hand-added child inherits its parent's (IFRS 17 I1, R4).
     */
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
