package tz.co.nlolo.lifeplatform.party;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tz.co.nlolo.lifeplatform.party.api.PhoneNumbers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** However a Tanzanian number is written, it is stored one way: +255XXXXXXXXX (2026-10-20). */
class PhoneNumbersTest {

    @ParameterizedTest
    @ValueSource(strings = {"+255712345678", "255712345678", "0712345678", "0712 345 678", "+255 712-345-678",
        "(0712) 345.678", "00255712345678", "  0712345678  "})
    void everyEverydayFormIsStoredTheOneWay(String written) {
        assertThat(PhoneNumbers.normalise(written)).isEqualTo("+255712345678");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void aBlankIsNoPhoneNotAnError(String written) {
        assertThat(PhoneNumbers.normalise(written)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"071234567", "07123456789", "+254712345678", "712345678", "0012345678", "phone",
        "+2550712345678"})
    void anythingElseIsRefusedInWords(String written) {
        assertThatThrownBy(() -> PhoneNumbers.normalise(written))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Enter a Tanzanian phone number");
    }
}
