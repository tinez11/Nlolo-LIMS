package tz.co.nlolo.lifeplatform.finaccounting.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ShareIssueRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 14);

    @Test
    void sharesAtNominalAreAllCapital() {
        var s = ShareIssueRules.split(1_000_000, new BigDecimal("1000"), new BigDecimal("1000"), new BigDecimal("1000000000"));
        assertThat(s.capital()).isEqualByComparingTo("1000000000.00");
        assertThat(s.premium()).isEqualByComparingTo("0.00");
    }

    @Test
    void anythingAboveNominalIsPremium() {
        var s = ShareIssueRules.split(1_000_000, new BigDecimal("1000"), new BigDecimal("1500"), new BigDecimal("1500000000"));
        assertThat(s.capital()).isEqualByComparingTo("1000000000.00");
        assertThat(s.premium()).isEqualByComparingTo("500000000.00");
    }

    @Test
    void aDiscountAPartPaymentAndAFutureDateAreRefused() {
        assertThat(ShareIssueRules.problems(10, new BigDecimal("1000"), new BigDecimal("900"), new BigDecimal("9000"),
            TODAY, TODAY)).containsExactly("Shares cannot be issued below their nominal value of 1,000.00");
        assertThat(ShareIssueRules.problems(10, new BigDecimal("1000"), new BigDecimal("1000"), new BigDecimal("9000"),
            TODAY, TODAY)).containsExactly("The amount paid must be shares × price: 10,000.00");
        assertThat(ShareIssueRules.problems(10, new BigDecimal("1000"), new BigDecimal("1000"), new BigDecimal("10000"),
            TODAY.plusDays(1), TODAY)).containsExactly("The money cannot be received in the future");
        assertThat(ShareIssueRules.problems(10, new BigDecimal("1000"), new BigDecimal("1000"), new BigDecimal("10000"),
            TODAY, TODAY)).isEmpty();
    }

    @Test
    void preferenceSharesGoToTheirOwnAccount() {
        assertThat(ShareIssueRules.capitalAccount("ORDINARY")).isEqualTo("3110");
        assertThat(ShareIssueRules.capitalAccount("PREFERENCE")).isEqualTo("3130");
    }
}
