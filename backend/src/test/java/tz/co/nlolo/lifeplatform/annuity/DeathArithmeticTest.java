package tz.co.nlolo.lifeplatform.annuity;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.annuity.domain.DeathArithmetic;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** What an annuitant's last death owes (product step 5, spec section 5.2). */
class DeathArithmeticTest {

    private static BigDecimal m(String v) { return new BigDecimal(v); }

    @Test
    void theRefundIsThePriceLessPaidLessGuaranteedStillToCome() {
        assertThat(DeathArithmetic.capitalRefund(m("50000000.00"), m("10584000.00"), m("24696000.00")))
            .isEqualByComparingTo("14720000.00");
    }

    @Test
    void theRefundIsNeverNegative() {
        assertThat(DeathArithmetic.capitalRefund(m("1000.00"), m("2000.00"), BigDecimal.ZERO)).isEqualByComparingTo("0.00");
    }

    @Test
    void anUnprotectedFormRefundsNothing() {
        var outcome = DeathArithmetic.lastDeath(false, m("50000000.00"), m("882000.00"), BigDecimal.ZERO, BigDecimal.ZERO);
        assertThat(outcome.refundPayable()).isEqualByComparingTo("0.00");
        assertThat(outcome.overpayment()).isEqualByComparingTo("0.00");
    }

    @Test
    void incomePaidAfterTheDeathCountsAgainstTheGuaranteeFirst() {
        // 588,000 paid after the death, all of it inside a guarantee that owed 1,000,000 more: nothing owed back.
        var outcome = DeathArithmetic.lastDeath(false, m("50000000.00"), m("882000.00"), m("1000000.00"), m("588000.00"));
        assertThat(outcome.overpayment()).isEqualByComparingTo("0.00");
    }

    @Test
    void thenAgainstTheRefundAndOnlyTheRestIsOwedBack() {
        // No guarantee; refund 300,000 (price 1,182,000 less 882,000 paid in life); 588,000 paid after the death.
        var outcome = DeathArithmetic.lastDeath(true, m("1182000.00"), m("882000.00"), BigDecimal.ZERO, m("588000.00"));
        assertThat(outcome.refundPayable()).isEqualByComparingTo("0.00");
        assertThat(outcome.overpayment()).isEqualByComparingTo("288000.00");
    }

    @Test
    void aGuaranteeAndCapitalProtectionNeverPayTheSameMoneyTwice() {
        // 50,000,000 price; 10,584,000 paid in life; 24,696,000 the guarantee still pays: refund 14,720,000.
        var outcome = DeathArithmetic.lastDeath(true, m("50000000.00"), m("10584000.00"), m("24696000.00"), BigDecimal.ZERO);
        assertThat(outcome.refundPayable()).isEqualByComparingTo("14720000.00");
    }
}
