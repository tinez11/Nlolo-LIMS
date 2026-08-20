package tz.co.nlolo.lifeplatform.regreporting;

import tz.co.nlolo.lifeplatform.regreporting.api.MetricKind;
import tz.co.nlolo.lifeplatform.regreporting.domain.MetricName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetricNameTest {

    /** The design spec's §5 enumerates exactly seventeen metrics. If this number changes, the
     * spec's swappability claim ("any TIRA line that is one of the seventeen...") changes with
     * it and must be updated in the same commit -- which is the point of asserting it. */
    @Test
    void thereAreExactlySeventeenMetrics() {
        assertThat(MetricName.values()).hasSize(17);
    }

    @Test
    void onlyTheTwoInForceMetricsAreStock() {
        assertThat(java.util.Arrays.stream(MetricName.values())
                .filter(m -> m.kind() == MetricKind.STOCK).map(Enum::name).toList())
            .containsExactlyInAnyOrder("POLICIES_IN_FORCE", "SUM_ASSURED_IN_FORCE");
    }

    /** Every name must fit return_definition_line.metric_name / return_line.metric_name,
     * VARCHAR(40) in regreporting/V2. M7 shipped a CHECK admitting a value the column could not
     * store; this is the cheap guard against the same class of defect. */
    @Test
    void everyMetricNameFitsItsColumn() {
        for (MetricName m : MetricName.values()) {
            assertThat(m.name().length()).as("%s must fit VARCHAR(40)", m).isLessThanOrEqualTo(40);
        }
    }

    /** A money metric's value carries a currency; a count's does not. The REST layer and
     * return_line.currency both depend on this being decidable from the metric alone. */
    @Test
    void moneyMetricsAreDistinguishableFromCounts() {
        assertThat(MetricName.SUM_ASSURED_IN_FORCE.isMonetary()).isTrue();
        assertThat(MetricName.CLAIMS_SETTLED_AMOUNT.isMonetary()).isTrue();
        assertThat(MetricName.PREMIUM_COLLECTED.isMonetary()).isTrue();
        assertThat(MetricName.POLICIES_IN_FORCE.isMonetary()).isFalse();
        assertThat(MetricName.CLAIMS_SETTLED.isMonetary()).isFalse();
    }
}
