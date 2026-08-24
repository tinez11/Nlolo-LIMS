package tz.co.nlolo.lifeplatform.reinsurance;

import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyType;
import tz.co.nlolo.lifeplatform.reinsurance.domain.ReinsuranceTreaty;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link ReinsuranceTreaty#isActiveOn} in isolation -- the predicate treaty selection rests on.
 * The selection ORDERING (newest effective_from, ties by treatyId) is exercised against a real
 * database in {@code ReinsuranceApiIntegrationTest}, since it is a query concern. */
class TreatySelectionTest {

    private static ReinsuranceTreaty treaty(LocalDate from, LocalDate to) {
        return new ReinsuranceTreaty(UUID.randomUUID(), "Test Re", TreatyType.QUOTA_SHARE,
            BigDecimal.ZERO, "TZS", new BigDecimal("30.00"), from, to, "actuary");
    }

    @Test
    void aTreatyIsActiveInsideItsWindow() {
        ReinsuranceTreaty t = treaty(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        assertThat(t.isActiveOn(LocalDate.of(2026, 6, 1))).isTrue();
    }

    @Test
    void windowBoundariesAreInclusiveOnBothEnds() {
        ReinsuranceTreaty t = treaty(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        assertThat(t.isActiveOn(LocalDate.of(2026, 1, 1))).isTrue();
        assertThat(t.isActiveOn(LocalDate.of(2026, 12, 31))).isTrue();
    }

    @Test
    void aTreatyIsInactiveOutsideItsWindow() {
        ReinsuranceTreaty t = treaty(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        assertThat(t.isActiveOn(LocalDate.of(2025, 12, 31))).isFalse();
        assertThat(t.isActiveOn(LocalDate.of(2027, 1, 1))).isFalse();
    }

    @Test
    void anOpenEndedTreatyNeverExpiresByDate() {
        ReinsuranceTreaty t = treaty(LocalDate.of(2026, 1, 1), null);
        assertThat(t.isActiveOn(LocalDate.of(2099, 1, 1))).isTrue();
    }

    @Test
    void anExpiredTreatyIsNeverActiveEvenInsideItsWindow() {
        ReinsuranceTreaty t = treaty(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        t.expire("staff-1");
        assertThat(t.isActiveOn(LocalDate.of(2026, 6, 1))).isFalse();
    }
}
