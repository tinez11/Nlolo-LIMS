package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * An account charge (product V32), taken by the versions that pick it (V34) and by policies issued on a choice of their own.
 *
 * @param when when it is taken: DEPOSIT (every deposit), PREMIUM (premiums and top-ups), TRANSFER_IN, WITHDRAWAL,
 *     MONTHLY, YEARLY, OPENING or MATURITY
 * @param amountType FLAT (an amount in {@code currency}) or PERCENT (of the deposit, the withdrawal or the balance)
 * @param fromPolicyYear the first policy year it is taken in
 * @param toPolicyYear the last, or null for every year after
 * @param active whether it may still be chosen; a version or policy already on it keeps it
 */
public record AccountChargeView(UUID chargeId, String name, String description, String when, String amountType,
                                BigDecimal amount, String currency, int fromPolicyYear, Integer toPolicyYear,
                                boolean active, String createdBy, Instant createdAt) {

    /** Whether it is taken in {@code policyYear}. */
    public boolean covers(int policyYear) {
        return policyYear >= fromPolicyYear && (toPolicyYear == null || policyYear <= toPolicyYear);
    }

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
