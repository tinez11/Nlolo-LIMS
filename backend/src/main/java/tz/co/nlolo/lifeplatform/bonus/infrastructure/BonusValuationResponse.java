package tz.co.nlolo.lifeplatform.bonus.infrastructure;

import tz.co.nlolo.lifeplatform.bonus.api.BonusValuation;

/** What bonuses are worth at a date: attached, interim, terminal and their total, with the rates they were valued at. */
public record BonusValuationResponse(MoneyResponse attached, MoneyResponse interim, MoneyResponse terminal,
                                     MoneyResponse total, String interimRatePercent, String terminalRatePercent,
                                     String declarationId) {
    static BonusValuationResponse from(BonusValuation v, String currency) {
        return new BonusValuationResponse(MoneyResponse.of(v.attached(), currency), MoneyResponse.of(v.interim(), currency),
            MoneyResponse.of(v.terminal(), currency), MoneyResponse.of(v.total(), currency),
            PolicyBonusResponse.rate(v.interimRatePercent()), PolicyBonusResponse.rate(v.terminalRatePercent()),
            v.declarationId() != null ? v.declarationId().toString() : null);
    }
}
