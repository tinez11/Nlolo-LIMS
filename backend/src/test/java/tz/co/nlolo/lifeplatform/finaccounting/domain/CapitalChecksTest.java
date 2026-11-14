package tz.co.nlolo.lifeplatform.finaccounting.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class CapitalChecksTest {

    @Test
    void allThreeMet() {
        var r = CapitalChecks.of(new BigDecimal("1000000000"), new BigDecimal("1000000000"), 400, 1000,
            new BigDecimal("1000000000"));
        assertThat(r.books().state()).isEqualTo("MET");
        assertThat(r.tanzanian().state()).isEqualTo("MET");
        assertThat(r.tanzanian().figure()).isEqualTo("40.0%");
        assertThat(r.minimum().state()).isEqualTo("MET");
    }

    @Test
    void aDifferenceUnderOneThirdAndShortCapitalAreNotMet() {
        var r = CapitalChecks.of(new BigDecimal("1000"), new BigDecimal("900"), 333, 1000, new BigDecimal("2000"));
        assertThat(r.books().state()).isEqualTo("NOT_MET");
        assertThat(r.books().figure()).isEqualTo("100.00");
        assertThat(r.tanzanian().state()).isEqualTo("NOT_MET");
        assertThat(r.tanzanian().figure()).isEqualTo("33.3%");
        assertThat(r.minimum().state()).isEqualTo("NOT_MET");
        assertThat(r.minimum().figure()).isEqualTo("1,100.00");
    }

    @Test
    void noMinimumIsNotSetAndNoSharesIsNotMet() {
        var r = CapitalChecks.of(BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, null);
        assertThat(r.minimum().state()).isEqualTo("NOT_SET");
        assertThat(r.minimum().figure()).isNull();
        assertThat(r.tanzanian().state()).isEqualTo("NOT_MET");
        assertThat(r.books().state()).isEqualTo("MET");
    }

    @Test
    void exactlyOneThirdIsMet() {
        assertThat(CapitalChecks.of(BigDecimal.ONE, BigDecimal.ONE, 1, 3, null).tanzanian().state()).isEqualTo("MET");
    }
}
