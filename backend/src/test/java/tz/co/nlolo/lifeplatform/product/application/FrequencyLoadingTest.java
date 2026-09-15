package tz.co.nlolo.lifeplatform.product.application;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.product.api.FrequencyLoading;
import tz.co.nlolo.lifeplatform.product.api.PremiumFrequency;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The frequency loading arithmetic, exercised without a Spring context.
 *
 * <p>This is the number a monthly payer is actually charged, so it gets cheap edge coverage of its
 * own rather than only being observed through a quote. Same arrangement as
 * {@code ProductCoverageRuleTest}.
 */
class FrequencyLoadingTest {

    private static final BigDecimal ANNUAL = new BigDecimal("12000");

    @Test
    void anUnloadedVersionChargesTheAnnualPremiumWhateverTheFrequency() {
        FrequencyLoading none = FrequencyLoading.none();
        for (PremiumFrequency f : PremiumFrequency.values()) {
            assertThat(none.applyTo(ANNUAL, f)).isEqualByComparingTo(ANNUAL);
        }
    }

    @Test
    void anEightPercentMonthlyLoadingRaisesTheAnnualPremiumByEightPercent() {
        FrequencyLoading loading = new FrequencyLoading(new BigDecimal("8"), new BigDecimal("3"));
        assertThat(loading.applyTo(ANNUAL, PremiumFrequency.MONTHLY))
            .isEqualByComparingTo(new BigDecimal("12960"));
    }

    @Test
    void quarterlyUsesItsOwnPercent() {
        FrequencyLoading loading = new FrequencyLoading(new BigDecimal("8"), new BigDecimal("3"));
        assertThat(loading.applyTo(ANNUAL, PremiumFrequency.QUARTERLY))
            .isEqualByComparingTo(new BigDecimal("12360"));
    }

    @Test
    void annualIsNeverLoaded() {
        FrequencyLoading loading = new FrequencyLoading(new BigDecimal("8"), new BigDecimal("3"));
        assertThat(loading.percentFor(PremiumFrequency.ANNUALLY)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(loading.applyTo(ANNUAL, PremiumFrequency.ANNUALLY)).isEqualByComparingTo(ANNUAL);
    }

    @Test
    void nullMeansUnloaded() {
        FrequencyLoading loading = new FrequencyLoading(null, null);
        assertThat(loading.monthlyPercent()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(loading.quarterlyPercent()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void aLoadingOutsideZeroToOneHundredIsRefused() {
        assertThatThrownBy(() -> new FrequencyLoading(new BigDecimal("-1"), BigDecimal.ZERO))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Monthly");
        assertThatThrownBy(() -> new FrequencyLoading(BigDecimal.ZERO, new BigDecimal("101")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Quarterly");
    }
}
