package tz.co.nlolo.lifeplatform.reinsurance;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.reinsurance.domain.ReinsuranceGroupKey;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** IFRS 17 I5a: reinsurance contracts held are their own groups (para 61), one per treaty and year. */
class ReinsuranceGroupKeyTest {

    @Test
    void aTreatysGroupIsItsShortIdAndTheYearItBegan() {
        UUID treaty = UUID.fromString("ab12cd34-0000-4000-8000-000000000001");
        assertThat(ReinsuranceGroupKey.of(treaty, LocalDate.of(2026, 9, 28))).isEqualTo("RI-AB12CD34-2026");
        assertThat(ReinsuranceGroupKey.of(treaty, LocalDate.of(2026, 9, 28))).hasSizeLessThanOrEqualTo(40);
        assertThat(ReinsuranceGroupKey.isReinsurance("RI-AB12CD34-2026")).isTrue();
        assertThat(ReinsuranceGroupKey.isReinsurance("TERM-GMM-2026-REM")).isFalse();
    }
}
