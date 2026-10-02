package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import tz.co.nlolo.lifeplatform.accumulation.api.ClosingQuote;

/** What a closing today would pay, before any surrender charge. */
public record ClosingQuoteResponse(String policyNumber, String asOf, MoneyResponse balance, MoneyResponse interestToDate,
                                   MoneyResponse value) {
    static ClosingQuoteResponse from(ClosingQuote q) {
        return new ClosingQuoteResponse(q.policyNumber(), q.asOf().toString(), MoneyResponse.of(q.balance(), q.currency()),
            MoneyResponse.of(q.interestToDate(), q.currency()), MoneyResponse.of(q.value(), q.currency()));
    }
}
