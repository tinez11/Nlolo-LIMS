package tz.co.nlolo.lifeplatform.policyloan.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PolicyLoanApi {
    LoanView originateLoan(String policyNumber, BigDecimal requestedAmount, String currency, String payeeRef, String originatedBy);
    LoanView getLoan(UUID loanId);
    List<LoanView> listLoansForPolicy(String policyNumber);

    /**
     * How much the policy can borrow now (2026-11-07): its value × the product's maximum loan, less what is borrowed
     * and the interest owed on it. Origination is refused above {@link LoanLimitView#canBorrowNow()}.
     */
    LoanLimitView loanLimit(String policyNumber);
    /**
     * Money confirmed for this loan (money movement step 1, 2026-11-04): a deposit For: Loan a second person approved,
     * or a mobile-money request the rail confirmed. Applies at most what the loan owes, interest first; anything above
     * that is published as {@code policyloan.LoanOverpaid}, so it is posted and never silently kept.
     */
    LoanView applyConfirmedRepayment(UUID loanId, BigDecimal amount, String currency, String paymentReference,
                                     String receiptMethod, UUID paymentId, String recordedBy);

    /**
     * Ask the payer's phone for a repayment (money movement step 1). Publishes {@code policyloan.RepaymentRequested} for
     * payment to collect; nothing is applied until it is confirmed. Refused for more than the loan owes now.
     */
    void requestRepayment(UUID loanId, String payerRef, BigDecimal amount, String currency, String idempotencyKey);

    /**
     * A second person releases a loan's payout (money movement step 4): payouts.approve, or the person who took the
     * loan holding payouts.approve-own. Payment is then asked to pay it.
     */
    LoanView approvePayout(UUID loanId, String approvedBy);

    /** A second person refuses a loan's payout, saying why: nothing is paid and the reserved loan value is freed. */
    LoanView refusePayout(UUID loanId, String refusedBy, String reason);

    /** Loans waiting for their payout to be released -- the approvals inbox's rows. */
    List<LoanView> listLoansAwaitingPayoutApproval();

    // Driven by policyloan.application.PaymentEventListener consuming payment's confirmation
    // events (M5). Formerly internal-only test seams standing in for those events.
    LoanView markDisbursed(UUID loanId, String gatewayReference, Instant disbursedAt);
    LoanView markDisbursementFailed(UUID loanId, String reason);

    /**
     * Runs the forced-lapse shortfall test for one loan and acts on the result:
     * {@code docs/01-domain-map.md:224} defines Forced Lapse as firing when "loan balance plus
     * interest exceeds cash value". Idempotent and safe to call on a healthy loan, which it
     * leaves untouched apart from clearing its review flag.
     *
     * <p>This is the tenant-scoped half of the accrual sweep. The sweep
     * (configure-loan-interest-accrual.sql) grows balances cross-tenant in SQL and flags each
     * loan it touched; it cannot run this test itself, because cash value lives in
     * {@code policy.policy_account} and this module reads that only through {@code PolicyApi},
     * and Java cannot sweep cross-tenant at all (no {@code TenantContext} means RLS shows a
     * background thread zero rows).
     */
    LoanView evaluateForcedLapse(UUID loanId);

    /** The review queue the accrual sweep fills: loans whose balance grew and whose shortfall
     * test has not been run since. Tenant-scoped, so a caller drains only its own. */
    List<LoanView> listLoansPendingForcedLapseReview();

    /** Forces a lapse unconditionally, bypassing the shortfall test -- for a staff decision made
     * on grounds this module cannot see. {@link #evaluateForcedLapse} is the automatic path. */
    LoanView triggerForcedLapse(UUID loanId, String reason);
}
