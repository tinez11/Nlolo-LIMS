package tz.co.nlolo.lifeplatform.policyloan.infrastructure;

import tz.co.nlolo.lifeplatform.policyloan.api.LoanStatus;
import tz.co.nlolo.lifeplatform.policyloan.api.LoanView;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Same wire-shape note as Task 4's PolicyResponseDto: openapi-policyloan.yaml's LoanView
 * schema nests principalAmount/outstandingBalance as Money objects, but policyloan.api.LoanView
 * (Task 6) is flattened (separate *Amount/*Currency fields). This is the translation layer.
 * takenAt, principalOutstanding and interestOutstanding: 2026-11-07, the Loans page. */
public record LoanResponseDto(UUID loanId, String policyNumber, MoneyDto principalAmount, MoneyDto outstandingBalance,
                               BigDecimal currentInterestRate, LoanStatus status, String payeeRef, String takenBy,
                               String payoutDecidedBy, String payoutRefusalReason, Instant takenAt,
                               MoneyDto principalOutstanding, MoneyDto interestOutstanding) {

    public static LoanResponseDto from(LoanView view) {
        return new LoanResponseDto(view.loanId(), view.policyNumber(),
            new MoneyDto(view.principalAmount().toPlainString(), view.principalCurrency()),
            new MoneyDto(view.outstandingBalance().toPlainString(), view.outstandingCurrency()),
            view.currentInterestRate(), view.status(), view.payeeRef(), view.takenBy(), view.payoutDecidedBy(),
            view.payoutRefusalReason(), view.takenAt(),
            money(view.principalOutstanding(), view.principalCurrency()),
            money(view.interestOutstanding(), view.principalCurrency()));
    }

    private static MoneyDto money(BigDecimal amount, String currency) {
        return new MoneyDto((amount == null ? BigDecimal.ZERO : amount).setScale(2, java.math.RoundingMode.HALF_EVEN)
            .toPlainString(), currency);
    }
}
