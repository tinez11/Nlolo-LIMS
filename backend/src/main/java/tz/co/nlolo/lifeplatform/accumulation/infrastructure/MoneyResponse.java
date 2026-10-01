package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import java.math.BigDecimal;

/** The platform's money shape on the wire: a decimal string and a currency, never a JSON number. */
public record MoneyResponse(String amount, String currencyCode) {
    static MoneyResponse of(BigDecimal amount, String currency) {
        return new MoneyResponse(amount.toPlainString(), currency);
    }
}
