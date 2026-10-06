package tz.co.nlolo.lifeplatform.policyloan.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * What a loan owes, split into principal and interest (IFRS 17 I3b, posting guide E-03/E-06), replayed from its
 * transactions in order. A repayment pays the interest accrued so far first, then principal -- the usual allocation,
 * and the one the ledger's 2126 (interest receivable) and 2125 (principal) need to agree with. Pure: no I/O.
 */
public record LoanPosition(BigDecimal principalOutstanding, BigDecimal interestOutstanding) {

    public static LoanPosition of(BigDecimal principal, List<LoanTransaction> inOrder) {
        BigDecimal principalDue = principal;
        BigDecimal interestDue = BigDecimal.ZERO;
        for (LoanTransaction t : inOrder) {
            switch (t.getTransactionType()) {
                case "INTEREST_ACCRUAL" -> interestDue = interestDue.add(t.getAmount());
                case "REPAYMENT", "SETTLEMENT" -> {
                    BigDecimal toInterest = t.getAmount().min(interestDue.max(BigDecimal.ZERO));
                    interestDue = interestDue.subtract(toInterest);
                    principalDue = principalDue.subtract(t.getAmount().subtract(toInterest));
                }
                default -> { /* DISBURSEMENT and REVERSAL: the principal is the loan's own amount */ }
            }
        }
        return new LoanPosition(principalDue, interestDue);
    }

    /** How a payment of {@code amount} against this position splits: interest first, then principal. */
    public BigDecimal interestPartOf(BigDecimal amount) {
        return amount.min(interestOutstanding.max(BigDecimal.ZERO));
    }
}
