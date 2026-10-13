package tz.co.nlolo.lifeplatform.omnichannel.application;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The payment schedule's limited-pay note (2026-10-13): six monthly premiums on a 12-month term read, row by row, as
 * cover ending after month six. The note says cover runs to the end of the term.
 */
class LimitedPayNoteTest {

    private static final LocalDate END = LocalDate.of(2027, 10, 13);

    @Test
    void aLimitedPayPolicySaysPremiumsStopBeforeCoverDoes() {
        assertThat(PolicyDocuments.limitedPayNote(12, 6, END)).isEqualTo(
            "Premiums are paid for the first 6 months of the 12-month term. Cover continues until 13/10/2027"
                + " with nothing more to pay.");
        assertThat(PolicyDocuments.limitedPayNote(12, 1, END)).startsWith("Premiums are paid for the first month of");
    }

    @Test
    void premiumsForTheWholeTermOrNoTermSayNothing() {
        assertThat(PolicyDocuments.limitedPayNote(12, 12, END)).isNull();
        assertThat(PolicyDocuments.limitedPayNote(null, null, null)).isNull();
        assertThat(PolicyDocuments.limitedPayNote(12, null, END)).isNull();
        assertThat(PolicyDocuments.limitedPayNote(12, 6, null)).isNull();
    }
}
