package tz.co.nlolo.lifeplatform.finaccounting.domain;

import tz.co.nlolo.lifeplatform.finaccounting.api.AccountType;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;

import java.util.Map;
import java.util.Optional;

/**
 * EVERY MAPPING BELOW IS AN INVENTED PLACEHOLDER pending FINANCE sign-off -- the same treatment
 * M2 gave the underwriting decision engine, M7 gave commission and M8 gave cession. No document on
 * this platform specifies account codes or their debit/credit treatment.
 *
 * <p><b>This is an ACCRUAL ledger, which is why premium takes two entries, not one.</b> The
 * obligation arises when an invoice is GENERATED, so {@code PremiumInvoiceGenerated} raises
 * {@code 1200 Premium Receivable} against {@code 2200 Unearned Premium}; {@code PremiumCollected}
 * then settles the receivable against cash. Neither touches an income account, deliberately:
 * premium is EARNED as coverage is provided, and that earning pattern is LRC release -- C1-blocked
 * (Actuarial). M9 therefore accumulates unearned premium and recognises zero earned premium, and no
 * {@code 4xxx} account is even seeded.
 *
 * <p><b>{@code policy.PolicyIssued} maps to nothing, deliberately.</b> Issuing a policy moves no
 * cash and creates no immediate obligation -- the obligation attaches per invoice, which
 * {@code PremiumInvoiceGenerated} above captures. What genuinely belongs at issuance is LRC/CSM
 * initial recognition, which is C1-blocked.
 */
public final class PostingRule {

    private PostingRule() {}

    /** One balanced pair: which account is debited, which is credited. */
    public record AccountPair(String debitAccount, String creditAccount) {}

    public static final String CASH = "1000";
    public static final String PREMIUM_RECEIVABLE = "1200";
    public static final String REINSURANCE_RECOVERABLE = "1300";
    public static final String POLICY_LOAN_RECEIVABLE = "1400";
    public static final String UNEARNED_PREMIUM = "2200";
    public static final String REINSURANCE_PAYABLE = "2300";
    public static final String CLAIMS_EXPENSE = "5000";
    public static final String COMMISSION_EXPENSE = "5100";
    public static final String REINSURANCE_CEDED_PREMIUM = "5200";

    private static final Map<String, AccountPair> RULES = Map.of(
        "billing.PremiumInvoiceGenerated", new AccountPair(PREMIUM_RECEIVABLE, UNEARNED_PREMIUM),
        "billing.PremiumCollected",        new AccountPair(CASH, PREMIUM_RECEIVABLE),
        "claims.ClaimSettled",             new AccountPair(CLAIMS_EXPENSE, CASH),
        "distribution.CommissionPaid",     new AccountPair(COMMISSION_EXPENSE, CASH),
        "reinsurance.CessionRecorded",     new AccountPair(REINSURANCE_CEDED_PREMIUM, REINSURANCE_PAYABLE),
        "reinsurance.RecoveryConfirmed",   new AccountPair(REINSURANCE_RECOVERABLE, CLAIMS_EXPENSE),
        "policyloan.LoanDisbursed",        new AccountPair(POLICY_LOAN_RECEIVABLE, CASH),
        "policyloan.LoanRepaid",           new AccountPair(CASH, POLICY_LOAN_RECEIVABLE));

    /** Empty for any event with no accounting consequence -- which is most events on this
     * platform, and is a normal outcome rather than an error. */
    public static Optional<AccountPair> forEvent(String eventType) {
        return Optional.ofNullable(RULES.get(eventType));
    }

    /** The nine accounts M9 posts to, for seeding. Deliberately no 4xxx INCOME account. */
    public static Map<String, String> seedAccounts() {
        return Map.of(
            CASH, "Cash / Mobile Money",
            PREMIUM_RECEIVABLE, "Premium Receivable",
            REINSURANCE_RECOVERABLE, "Reinsurance Recoverable",
            POLICY_LOAN_RECEIVABLE, "Policy Loan Receivable",
            UNEARNED_PREMIUM, "Unearned Premium",
            REINSURANCE_PAYABLE, "Reinsurance Payable",
            CLAIMS_EXPENSE, "Claims Expense",
            COMMISSION_EXPENSE, "Commission Expense",
            REINSURANCE_CEDED_PREMIUM, "Reinsurance Ceded Premium");
    }

    /** ASSET/EXPENSE accounts are normally debit-balanced; LIABILITY/EQUITY/INCOME credit. */
    public static PostingDirection normalBalanceFor(String accountCode) {
        return accountCode.startsWith("2") || accountCode.startsWith("3") || accountCode.startsWith("4")
            ? PostingDirection.CR : PostingDirection.DR;
    }

    /**
     * Derived from the account code's leading digit, per the conventional five-block scheme
     * documented in finaccounting/V2 section 5: 1-ASSET, 2-LIABILITY, 3-EQUITY, 4-INCOME,
     * 5-EXPENSE. Moved here from {@code ChartOfAccountSeeder} (its original, seed-only home) when
     * {@code createAccount} needed the identical derivation for a hand-authored account code --
     * one rule, not two copies that could drift apart.
     */
    public static AccountType accountTypeFor(String accountCode) {
        return switch (accountCode.charAt(0)) {
            case '1' -> AccountType.ASSET;
            case '2' -> AccountType.LIABILITY;
            case '3' -> AccountType.EQUITY;
            case '4' -> AccountType.INCOME;
            case '5' -> AccountType.EXPENSE;
            default -> throw new IllegalArgumentException("Unrecognised account code block: " + accountCode);
        };
    }
}
