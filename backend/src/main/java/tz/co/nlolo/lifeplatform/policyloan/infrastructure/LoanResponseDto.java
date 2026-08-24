package tz.co.nlolo.lifeplatform.policyloan.infrastructure;

import tz.co.nlolo.lifeplatform.policyloan.api.LoanStatus;
import tz.co.nlolo.lifeplatform.policyloan.api.LoanView;

import java.math.BigDecimal;
import java.util.UUID;

/** Same wire-shape note as Task 4's PolicyResponseDto: openapi-policyloan.yaml's LoanView
 * schema nests principalAmount/outstandingBalance as Money objects, but policyloan.api.LoanView
 * (Task 6) is flattened (separate *Amount/*Currency fields). This is the translation layer. */
public record LoanResponseDto(UUID loanId, String policyNumber, MoneyDto principalAmount, MoneyDto outstandingBalance,
                               BigDecimal currentInterestRate, LoanStatus status) {

    public static LoanResponseDto from(LoanView view) {
        return new LoanResponseDto(view.loanId(), view.policyNumber(),
            new MoneyDto(view.principalAmount().toPlainString(), view.principalCurrency()),
            new MoneyDto(view.outstandingBalance().toPlainString(), view.outstandingCurrency()),
            view.currentInterestRate(), view.status());
    }
}
