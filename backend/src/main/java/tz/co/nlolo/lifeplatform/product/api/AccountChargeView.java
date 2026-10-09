package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * An account charge staff choose per savings policy (product V32).
 *
 * @param when when it is taken: DEPOSIT, WITHDRAWAL, MONTHLY, YEARLY, OPENING or MATURITY
 * @param amountType FLAT (an amount in {@code currency}) or PERCENT (of the deposit, the withdrawal or the balance)
 * @param active whether it may still be chosen; a policy already on it keeps it
 */
public record AccountChargeView(UUID chargeId, String name, String description, String when, String amountType,
                                BigDecimal amount, String currency, boolean active, String createdBy, Instant createdAt) {

    /** What this charge takes on {@code base} -- the deposit, the amount withdrawn or the balance, by when it is taken. */
    public BigDecimal on(BigDecimal base) {
        BigDecimal charge = "FLAT".equals(amountType) ? amount
            : base.multiply(amount).divide(new BigDecimal("100"), 2, RoundingMode.HALF_EVEN);
        return charge.setScale(2, RoundingMode.HALF_EVEN);
    }

    /** "Withdrawal fee (1.5%)", "Monthly fee (TZS 1,000)" -- how a statement line names it. */
    public String label() {
        String size = "FLAT".equals(amountType) ? currency + " " + amount.setScale(2, RoundingMode.HALF_EVEN).toPlainString()
            : amount.stripTrailingZeros().toPlainString() + "%";
        return name + " (" + size + ")";
    }
}
