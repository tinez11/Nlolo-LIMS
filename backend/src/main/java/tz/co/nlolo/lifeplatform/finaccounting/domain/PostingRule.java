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
 * {@code 1210 Premium Receivables} against {@code 2140 Unearned Premium}; {@code PremiumCollected}
 * then settles the receivable against cash. Neither touches an income account, deliberately:
 * premium is EARNED as coverage is provided, and that earning pattern is LRC release -- C1-blocked
 * (Actuarial). This module therefore accumulates unearned premium and recognises zero earned
 * premium. The {@code 4xxx} accounts now EXIST in the chart (finaccounting/V5 seeds the full
 * five-block structure -- see {@link ChartOfAccountBlueprint}) but no rule below targets one, so
 * they stay at zero until C1 lands.
 *
 * <p><b>{@code policy.PolicyIssued} maps to nothing, deliberately.</b> Issuing a policy moves no
 * cash and creates no immediate obligation -- the obligation attaches per invoice, which
 * {@code PremiumInvoiceGenerated} above captures. What genuinely belongs at issuance is LRC/CSM
 * initial recognition, which is C1-blocked.
 *
 * <p><b>ACCRUED LOAN INTEREST IS NOT POSTED, AND THAT IS A KNOWN GAP -- stated here rather than
 * left to be discovered.</b> {@code policyloan.accrue_loan_interest()}
 * (db-migrations/_post-migration/configure-loan-interest-accrual.sql) writes an
 * {@code INTEREST_ACCRUAL} entry to the loan's own ledger daily, so a loan's outstanding balance
 * is correct. Nothing reaches the GENERAL ledger, for two independent reasons:
 * <ul>
 *   <li>The accrual is a cross-tenant SQL sweep, and a Postgres backend session has no Spring
 *       {@code ApplicationEventPublisher}, so no {@code LoanInterestAccrued} event exists for
 *       this class to map. Publishing one would need the per-tenant Java half to detect
 *       unposted accruals and emit them -- real work, not a line in this map.</li>
 *   <li>The natural entry is DR {@code 1250 Policy Loan Receivables} / CR a {@code 4xxx} interest
 *       income account. Such an account now exists -- {@code 4300 Other Income} -- but nothing
 *       posts to any {@code 4xxx} account, per this class's note above. Loan interest is arguably
 *       outside the LRC/CSM problem that blocks {@code 4xxx} for premium -- it is not insurance
 *       revenue -- but that is a FINANCE call, not one to make silently while wiring a sweep.</li>
 * </ul>
 * Consequence, plainly: {@code 1250} reflects principal disbursed and repaid, never interest
 * earned, so interest income is understated by exactly the accrued amount. The trial balance
 * still balances, which is precisely why this needs saying out loud.
 */
public final class PostingRule {

    private PostingRule() {}

    /** One balanced pair: which account is debited, which is credited. */
    public record AccountPair(String debitAccount, String creditAccount) {}

    public static final String CASH = "1120";
    public static final String PREMIUM_RECEIVABLE = "1210";
    public static final String REINSURANCE_RECOVERABLE = "1240";
    public static final String POLICY_LOAN_RECEIVABLE = "1250";
    public static final String CLAIMS_PAYABLE = "2110";
    public static final String UNEARNED_PREMIUM = "2140";
    public static final String REINSURANCE_PAYABLE = "2220";
    public static final String CLAIMS_EXPENSE = "5100";
    public static final String COMMISSION_EXPENSE = "5200";
    public static final String REINSURANCE_CEDED_PREMIUM = "5500";

    private static final Map<String, AccountPair> RULES = Map.ofEntries(
        Map.entry("billing.PremiumInvoiceGenerated", new AccountPair(PREMIUM_RECEIVABLE, UNEARNED_PREMIUM)),
        Map.entry("billing.PremiumCollected",        new AccountPair(CASH, PREMIUM_RECEIVABLE)),
        // A premium credit is the invoice posting run backwards, for the part given back. It had
        // no rule at all: billing recorded 4,200 credited to a lender while the ledger went on
        // carrying the whole 13,800 as receivable -- the two could not be reconciled.
        Map.entry("billing.PremiumRefundDue",        new AccountPair(UNEARNED_PREMIUM, PREMIUM_RECEIVABLE)),
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
