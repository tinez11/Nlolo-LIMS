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

    // Internal-only test seam (not part of openapi-policyloan.yaml) standing in for a future
    // billing-driven forced-lapse (M4).
    LoanView triggerForcedLapse(UUID loanId, String reason);
}
