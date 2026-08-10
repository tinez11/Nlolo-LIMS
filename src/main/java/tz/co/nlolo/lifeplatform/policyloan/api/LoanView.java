package tz.co.nlolo.lifeplatform.policyloan.api;

import java.math.BigDecimal;
import java.util.UUID;

public record LoanView(UUID loanId, String policyNumber, BigDecimal principalAmount, String principalCurrency,
                        BigDecimal outstandingBalance, String outstandingCurrency, BigDecimal currentInterestRate, LoanStatus status) {}
