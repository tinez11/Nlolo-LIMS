package tz.co.nlolo.lifeplatform.policyloan.infrastructure;

import tz.co.nlolo.lifeplatform.policyloan.api.LoanLimitView;

import java.math.BigDecimal;

/** openapi-policyloan.yaml's LoanLimit: the server's figures for how much a policy can borrow now (2026-11-07). */
public record LoanLimitResponseDto(String policyNumber, boolean lends, MoneyDto value, BigDecimal maxLoanToValuePercent,
                                   MoneyDto limit, MoneyDto borrowed, MoneyDto interestOwed, MoneyDto canBorrowNow,
                                   BigDecimal annualInterestRate) {

    public static LoanLimitResponseDto from(LoanLimitView v) {
        return new LoanLimitResponseDto(v.policyNumber(), v.lends(), money(v.value(), v.currency()), v.maxLoanToValuePercent(),
            money(v.limit(), v.currency()), money(v.borrowed(), v.currency()), money(v.interestOwed(), v.currency()),
            money(v.canBorrowNow(), v.currency()), v.annualInterestRate());
    }

    private static MoneyDto money(BigDecimal amount, String currency) {
        return new MoneyDto(amount.setScale(2, java.math.RoundingMode.DOWN).toPlainString(), currency);
    }
}
