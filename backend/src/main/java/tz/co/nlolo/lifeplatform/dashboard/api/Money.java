package tz.co.nlolo.lifeplatform.dashboard.api;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money on the wire: a decimal string and its currency, never a JSON number (PRODUCT.md). The common {@code Money}
 * schema in {@code openapi-common.yaml}, which is also the console's own {@code Money} type in {@code lib/money.ts}.
 */
public record Money(String amount, String currencyCode) {

    /** Null in, null out: an absent amount is absent on the card, not "TZS 0.00". */
    public static Money of(BigDecimal amount, String currencyCode) {
        return amount == null || currencyCode == null
            ? null
            : new Money(amount.setScale(2, RoundingMode.HALF_UP).toPlainString(), currencyCode);
    }
}
