package tz.co.nlolo.lifeplatform.benefitpayout.application;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.benefitpayout.api.PayoutStateException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Finance finding 36 (2026-11-10): a pension lump sum was reviewed with payee "nothing" and paid. */
class MobileMoneyPayeeTest {

    @Test
    void everydayFormsOfATanzanianNumberAreStoredOneWay() {
        assertThat(BenefitPayoutApiImpl.mobileMoneyPayee("0712 345 678")).isEqualTo("+255712345678");
        assertThat(BenefitPayoutApiImpl.mobileMoneyPayee("255712345678")).isEqualTo("+255712345678");
        assertThat(BenefitPayoutApiImpl.mobileMoneyPayee("+255712345678")).isEqualTo("+255712345678");
    }

    @Test
    void anythingElseIsRefusedInWords() {
        assertThatThrownBy(() -> BenefitPayoutApiImpl.mobileMoneyPayee("nothing"))
            .isInstanceOf(PayoutStateException.class).hasMessageContaining("Tanzanian phone number");
        assertThatThrownBy(() -> BenefitPayoutApiImpl.mobileMoneyPayee("  "))
            .isInstanceOf(PayoutStateException.class).hasMessageContaining("mobile money number");
    }
}
