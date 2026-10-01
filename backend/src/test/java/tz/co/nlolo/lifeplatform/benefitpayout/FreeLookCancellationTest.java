package tz.co.nlolo.lifeplatform.benefitpayout;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.benefitpayout.api.PayoutStateException;
import tz.co.nlolo.lifeplatform.benefitpayout.domain.FreeLookCancellation;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The refund arithmetic and the two-person rule, without a database. */
class FreeLookCancellationTest {

    private static FreeLookCancellation request(String premiums, String deductions) {
        return FreeLookCancellation.request(UUID.randomUUID(), "POL-1", new BigDecimal(premiums),
            new BigDecimal(deductions), "TZS", "+255700000009", "req-1");
    }

    @Test
    void theRefundIsPremiumsLessDeductions() {
        assertThat(request("500000.00", "20000.00").getRefundAmount()).isEqualByComparingTo("480000.00");
    }

    @Test
    void deductionsMayConsumeThePremiumsExactly() {
        // A refund of nothing is legitimate -- the costs happened to equal what was paid. The
        // caller then requests no disbursement at all rather than a zero payment.
        assertThat(request("20000.00", "20000.00").getRefundAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void deductionsLargerThanThePremiumAreRefused() {
        // A refund can be nothing; it can never be a bill. Without this, exercising a statutory
        // right to walk away would leave the customer owing the insurer money.
        assertThatThrownBy(() -> request("10000.00", "20000.00"))
            .isInstanceOf(PayoutStateException.class)
            .hasMessage("Deductions of 20000.00 exceed the 10000.00 premiums collected");
    }

    @Test
    void aRefundNeedsSomewhereToSendIt() {
        assertThatThrownBy(() -> FreeLookCancellation.request(UUID.randomUUID(), "POL-1",
                new BigDecimal("500000.00"), BigDecimal.ZERO, "TZS", "  ", "req-1"))
            .isInstanceOf(PayoutStateException.class)
            .hasMessage("A free-look refund needs a payee reference");
    }

    @Test
    void theRequesterCannotApprove() {
        FreeLookCancellation c = request("500000.00", "0.00");
        assertThatThrownBy(() -> c.approve("req-1"))
            .isInstanceOf(PayoutStateException.class)
            .hasMessage("A free-look cancellation must be approved by someone other than the person "
                + "who requested it (req-1)");
        c.approve("apr-2");
        assertThat(c.getStatus()).isEqualTo("APPROVED");
        assertThat(c.getApprovedBy()).isEqualTo("apr-2");
    }

    @Test
    void aDecidedCancellationIsNotDecidedAgain() {
        FreeLookCancellation c = request("500000.00", "0.00");
        c.approve("apr-2");
        assertThatThrownBy(() -> c.approve("apr-3"))
            .isInstanceOf(PayoutStateException.class)
            .hasMessageContaining("is APPROVED, not awaiting approval");
    }

    @Test
    void aRedeliveredOutcomeCannotReopenAClosedCancellation() {
        FreeLookCancellation c = request("500000.00", "0.00");
        c.approve("apr-2");
        c.markPaid(UUID.randomUUID());
        assertThat(c.getStatus()).isEqualTo("PAID");
        // The rail resending a failure after a success must not rewrite history.
        c.markFailed();
        assertThat(c.getStatus()).isEqualTo("PAID");
    }
}
