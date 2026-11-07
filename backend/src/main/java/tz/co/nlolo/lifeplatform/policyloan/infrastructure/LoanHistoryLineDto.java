package tz.co.nlolo.lifeplatform.policyloan.infrastructure;

import tz.co.nlolo.lifeplatform.policyloan.api.LoanHistoryLine;

import java.time.Instant;

/** openapi-policyloan.yaml's LoanHistoryLine (2026-11-07). */
public record LoanHistoryLineDto(String kind, MoneyDto amount, Instant at, String reference) {

    public static LoanHistoryLineDto from(LoanHistoryLine line) {
        return new LoanHistoryLineDto(line.kind(), new MoneyDto(line.amount().setScale(2, java.math.RoundingMode.HALF_EVEN)
            .toPlainString(), line.currency()), line.at(), line.reference());
    }
}
