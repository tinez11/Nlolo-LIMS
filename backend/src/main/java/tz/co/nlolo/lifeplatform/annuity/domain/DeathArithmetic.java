package tz.co.nlolo.lifeplatform.annuity.domain;

import java.math.BigDecimal;

/**
 * What an annuitant's last death owes (product step 5, spec section 5.2). Pure.
 *
 * <p>Three figures go in, each gross:
 * <ul>
 *   <li>{@code paidBefore} -- income paid for due dates on or before the death;</li>
 *   <li>{@code guaranteed} -- every instalment the guarantee still owes for due dates after the death
 *       up to its end, whoever was actually paid them;</li>
 *   <li>{@code paidAfter} -- income already paid for due dates after the death (late notification).</li>
 * </ul>
 *
 * <p>The capital refund is the purchase price less what was paid during life less what the guarantee
 * still pays, never negative -- so a form with both a guarantee and capital protection never pays the
 * same money twice. Income paid after the death counts first against the guarantee it was owed under,
 * then against the refund; only what is left over is an overpayment owed back.
 */
public final class DeathArithmetic {

    private DeathArithmetic() {}

    public record Outcome(BigDecimal refundPayable, BigDecimal overpayment) {}

    public static BigDecimal capitalRefund(BigDecimal price, BigDecimal paidBefore, BigDecimal guaranteed) {
        return price.subtract(paidBefore).subtract(guaranteed).max(BigDecimal.ZERO).setScale(2);
    }

    /**
     * @param capitalProtected whether the form refunds the price at all
     */
    public static Outcome lastDeath(boolean capitalProtected, BigDecimal price, BigDecimal paidBefore,
                                    BigDecimal guaranteed, BigDecimal paidAfter) {
        BigDecimal refund = capitalProtected ? capitalRefund(price, paidBefore, guaranteed) : BigDecimal.ZERO.setScale(2);
        BigDecimal beyondGuarantee = paidAfter.subtract(guaranteed).max(BigDecimal.ZERO);
        BigDecimal refundPayable = refund.subtract(beyondGuarantee).max(BigDecimal.ZERO).setScale(2);
        BigDecimal overpayment = beyondGuarantee.subtract(refund).max(BigDecimal.ZERO).setScale(2);
        return new Outcome(refundPayable, overpayment);
    }
}
