package tz.co.nlolo.lifeplatform;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayoutRouteTest {

    @Test
    void aBlankMethodIsMobileMoneySoAnUntaughtCallerBehavesAsBefore() {
        assertThat(PayoutRoute.of(null, " +255700000001 ", null, null, null, null))
            .isEqualTo(PayoutRoute.mobileMoney("+255700000001"));
        assertThat(PayoutRoute.fromPayload(Map.of("payeeRef", "+255700000001")))
            .isEqualTo(PayoutRoute.mobileMoney("+255700000001"));
    }

    @Test
    void eachMethodNamesWhatItIsMissing() {
        assertThatThrownBy(() -> PayoutRoute.of("MOBILE_MONEY", " ", null, null, null, null))
            .hasMessage("Give the mobile-money number to pay");
        assertThatThrownBy(() -> PayoutRoute.of("CHEQUE", null, null, null, null, null))
            .hasMessage("Give the name the cheque is made out to");
        assertThatThrownBy(() -> PayoutRoute.of("EFT", null, "CRDB Bank", null, "Amina Owner", ""))
            .hasMessage("Give the bank account number");
        assertThatThrownBy(() -> PayoutRoute.of("CASH", "x", null, null, null, null))
            .hasMessage("Pay by mobile money, bank transfer or cheque");
    }

    @Test
    void aBankTransferIsPaidByHandToTheAccountNameAndSurvivesTheEvent() {
        PayoutRoute eft = PayoutRoute.of("eft", null, "CRDB Bank", " ", "Amina Owner", "0150123456");
        assertThat(eft.byHand()).isTrue();
        assertThat(eft.payee()).isEqualTo("Amina Owner");
        assertThat(eft.branch()).isNull();
        assertThat(eft.describe()).isEqualTo("CRDB Bank -- Amina Owner, 0150123456");
        assertThat(PayoutRoute.fromPayload(eft.toPayload())).isEqualTo(eft);
        assertThat(PayoutRoute.mobileMoney("+255700000001").byHand()).isFalse();
    }
}
