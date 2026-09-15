package tz.co.nlolo.lifeplatform.product.application;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.product.api.TiraFiling;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The filing's own rules, without a Spring context.
 *
 * <p>Validation lives in the record rather than in {@code ProductApiImpl} so no caller can
 * construct an invalid filing to pass anywhere — the same arrangement as
 * {@code EligibilityBounds}'s ordering invariant and {@code FrequencyLoading}'s 0-100 bounds.
 */
class TiraFilingTest {

    @Test
    void aFilingApprovedInThePastIsValid() {
        TiraFiling filing = new TiraFiling("TIRA/LIFE/2026/0042", LocalDate.of(2026, 1, 15));
        assertThat(filing.reference()).isEqualTo("TIRA/LIFE/2026/0042");
        assertThat(filing.approvalDate()).isEqualTo(LocalDate.of(2026, 1, 15));
    }

    @Test
    void aFilingApprovedTodayIsValid() {
        assertThatCode(() -> new TiraFiling("TIRA/LIFE/2026/0043", LocalDate.now()))
            .doesNotThrowAnyException();
    }

    @Test
    void aFilingApprovedTomorrowIsRefused() {
        assertThatThrownBy(() -> new TiraFiling("TIRA/LIFE/2026/0044", LocalDate.now().plusDays(1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("future");
    }

    @Test
    void aBlankReferenceIsRefused() {
        for (String blank : new String[] { null, "", "   " }) {
            assertThatThrownBy(() -> new TiraFiling(blank, LocalDate.of(2026, 1, 15)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reference");
        }
    }

    @Test
    void aMissingApprovalDateIsRefused() {
        assertThatThrownBy(() -> new TiraFiling("TIRA/LIFE/2026/0045", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("approval date");
    }

    @Test
    void theReferenceIsTrimmed() {
        assertThat(new TiraFiling("  TIRA/LIFE/2026/0046  ", LocalDate.of(2026, 1, 15)).reference())
            .isEqualTo("TIRA/LIFE/2026/0046");
    }
}
