package tz.co.nlolo.lifeplatform.benefitpayout;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.benefitpayout.api.InstalmentStatus;
import tz.co.nlolo.lifeplatform.benefitpayout.api.PayoutStateException;
import tz.co.nlolo.lifeplatform.benefitpayout.api.ProofOfLifeMethod;
import tz.co.nlolo.lifeplatform.benefitpayout.domain.PayoutInstalment;
import tz.co.nlolo.lifeplatform.product.api.PayoutKind;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The state machine that gets one payout paid, pinned before anything depends on it. Every rule
 * below is one the platform refuses on, so each is a 422 a person would otherwise meet only after
 * a second, deliberate click.
 */
class PayoutInstalmentTest {

    private static PayoutInstalment survival() {
        return new PayoutInstalment(UUID.randomUUID(), "POL-1", PayoutKind.SURVIVAL, 0, null,
            LocalDate.of(2031, 1, 15), new BigDecimal("100000.00"), "TZS");
    }

    private static PayoutInstalment maturity() {
        return new PayoutInstalment(UUID.randomUUID(), "POL-1", PayoutKind.MATURITY, 1, null,
            LocalDate.of(2046, 1, 15), new BigDecimal("1000000.00"), "TZS");
    }

    @Test
    void premiumsBehindHoldTheInstalmentInsteadOfLettingItFallDue() {
        PayoutInstalment i = survival();
        i.fallDue(false, null);
        assertThat(i.status()).isEqualTo(InstalmentStatus.ON_HOLD);
        assertThat(i.getStatusReason()).isEqualTo("Premiums are not paid up to the due date");

        i.release();
        assertThat(i.status()).isEqualTo(InstalmentStatus.DUE);
        assertThat(i.getStatusReason()).isNull();
    }

    @Test
    void aSurvivalPayoutNeedsProofOfLifeAndAMaturityDoesNot() {
        PayoutInstalment s = survival();
        s.fallDue(true, null);
        assertThatThrownBy(() -> s.review("rev", "+255700000001", null, null))
            .isInstanceOf(PayoutStateException.class)
            .hasMessage("A SURVIVAL payout needs proof that the life assured is alive");

        // A maturity is owed by the calendar alone -- nobody has to be alive for it to be due.
        PayoutInstalment m = maturity();
        m.fallDue(true, null);
        m.review("rev", "+255700000001", null, null);
        assertThat(m.status()).isEqualTo(InstalmentStatus.REVIEWED);
    }

    @Test
    void theReviewerCannotApproveTheirOwnPayout() {
        PayoutInstalment i = survival();
        i.fallDue(true, null);
        i.review("rev", "+255700000001", ProofOfLifeMethod.IN_PERSON, null);

        assertThatThrownBy(() -> i.approve("rev"))
            .hasMessage("A payout must be approved by someone other than the person who reviewed it (rev)");

        i.approve("apr");
        assertThat(i.status()).isEqualTo(InstalmentStatus.APPROVED);
        assertThat(i.getAttempts()).isEqualTo(1);
    }

    @Test
    void anApprovedPayoutCannotBeCancelledAndAFailedOneRetries() {
        PayoutInstalment i = maturity();
        i.fallDue(true, null);
        i.review("rev", "+255700000001", null, null);
        i.approve("apr");

        // A lapse arriving after the money went out must not fail the lapse -- so this is a
        // no-op rather than a throw.
        assertThat(i.cancel("Policy lapsed")).isFalse();
        assertThat(i.status()).isEqualTo(InstalmentStatus.APPROVED);

        i.markFailed();
        i.retry();
        assertThat(i.status()).isEqualTo(InstalmentStatus.APPROVED);
        // The second attempt is counted, which is what makes the payment key unique per try.
        assertThat(i.getAttempts()).isEqualTo(2);
    }

    @Test
    void restatementKeepsTheOriginalBesideTheNewFigure() {
        PayoutInstalment i = survival();
        i.restate(new BigDecimal("40000.00"), "Made paid-up");
        assertThat(i.getOriginalAmount()).isEqualByComparingTo("100000.00");
        assertThat(i.getCurrentAmount()).isEqualByComparingTo("40000.00");
        assertThat(i.getRestatementReason()).isEqualTo("Made paid-up");
    }

    @Test
    void aPaidInstalmentIsNotRestated() {
        PayoutInstalment i = maturity();
        i.fallDue(true, null);
        i.review("rev", "+255700000001", null, null);
        i.approve("apr");
        i.markPaid(UUID.randomUUID());

        i.restate(new BigDecimal("1.00"), "Made paid-up");
        assertThat(i.getCurrentAmount()).isEqualByComparingTo("1000000.00");
    }

    @Test
    void reviewNeedsAPayee() {
        PayoutInstalment i = maturity();
        i.fallDue(true, null);
        assertThatThrownBy(() -> i.review("rev", "  ", null, null))
            .hasMessage("A payout needs a payee reference");
    }

    @Test
    void aPremiumReturnLearnsItsAmountWhenItFallsDue() {
        PayoutInstalment rop = new PayoutInstalment(UUID.randomUUID(), "POL-1", PayoutKind.RETURN_OF_PREMIUM,
            0, null, LocalDate.of(2046, 1, 15), null, "TZS");
        assertThat(rop.getCurrentAmount()).isNull();

        rop.fallDue(true, new BigDecimal("600000.00"));
        assertThat(rop.getOriginalAmount()).isEqualByComparingTo("600000.00");
        assertThat(rop.getCurrentAmount()).isEqualByComparingTo("600000.00");
    }

    @Test
    void aCancelledInstalmentComesBackOnReinstatement() {
        PayoutInstalment i = survival();
        assertThat(i.cancel("Policy lapsed")).isTrue();
        i.restore();
        assertThat(i.status()).isEqualTo(InstalmentStatus.SCHEDULED);
        assertThat(i.getStatusReason()).isNull();
    }
}
