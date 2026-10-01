package tz.co.nlolo.lifeplatform.product.infrastructure;

import jakarta.validation.constraints.NotNull;
import tz.co.nlolo.lifeplatform.product.api.*;

import java.math.BigDecimal;

/**
 * One payout row on the wire. Only the shape Jackson needs is constrained here -- every rule about
 * which rows a category may carry, and which terms a row makes necessary, lives in
 * {@code PayoutPlanValidator}, so the HTTP and internal paths refuse identically.
 */
public record PayoutRowRequest(@NotNull PayoutKind kind, Integer fromPolicyYear, Integer toPolicyYear,
                               @NotNull PayoutAmountBasis amountBasis, @NotNull BigDecimal amountValue,
                               PayoutFrequency frequency) {

    public PayoutRowInput toInput() {
        return new PayoutRowInput(kind, fromPolicyYear, toPolicyYear, amountBasis, amountValue, frequency);
    }
}
