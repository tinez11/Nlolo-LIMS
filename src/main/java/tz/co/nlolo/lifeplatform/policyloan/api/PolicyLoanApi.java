package tz.co.nlolo.lifeplatform.policyloan.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface PolicyLoanApi {
    LoanView originateLoan(String policyNumber, BigDecimal requestedAmount, String currency, String payeeRef, String originatedBy);
    LoanView getLoan(UUID loanId);
    List<LoanView> listLoansForPolicy(String policyNumber);
    LoanView recordRepayment(UUID loanId, BigDecimal amount, String currency, String paymentReference, String recordedBy);

    // Internal-only test seams (not part of openapi-policyloan.yaml) standing in for
    // payment.DisbursementCompleted (M5) and a future billing-driven forced-lapse (M4).
    LoanView markDisbursed(UUID loanId);
    LoanView triggerForcedLapse(UUID loanId, String reason);
}
