package tz.co.nlolo.lifeplatform.policyloan.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PolicyLoanApi {
    LoanView originateLoan(String policyNumber, BigDecimal requestedAmount, String currency, String payeeRef, String originatedBy);
    LoanView getLoan(UUID loanId);
    List<LoanView> listLoansForPolicy(String policyNumber);
    LoanView recordRepayment(UUID loanId, BigDecimal amount, String currency, String paymentReference, String recordedBy);

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
