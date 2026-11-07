package tz.co.nlolo.lifeplatform.policyloan.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One loan. {@code takenAt}, {@code principalOutstanding} and {@code interestOutstanding} (2026-11-07, the user: the
 * Loans page shows everything a loan needs) say when it was taken and what the outstanding balance is made of, as the
 * server splits it -- interest first, then principal.
 */
public record LoanView(UUID loanId, String policyNumber, BigDecimal principalAmount, String principalCurrency,
                        BigDecimal outstandingBalance, String outstandingCurrency, BigDecimal currentInterestRate, LoanStatus status,
                        String payeeRef, String takenBy, String payoutDecidedBy, String payoutRefusalReason,
                        Instant takenAt, BigDecimal principalOutstanding, BigDecimal interestOutstanding) {}
