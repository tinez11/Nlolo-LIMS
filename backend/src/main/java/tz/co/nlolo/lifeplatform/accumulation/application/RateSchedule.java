package tz.co.nlolo.lifeplatform.accumulation.application;

import tz.co.nlolo.lifeplatform.accumulation.domain.RateDeclaration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;

/** {@code max(latest approved declaration effective by d, the version's guarantee)}, per day. */
final class RateSchedule implements Function<LocalDate, BigDecimal> {

    private final List<RateDeclaration> approvedAscending;
    private final BigDecimal guarantee;

    RateSchedule(List<RateDeclaration> approvedAscending, BigDecimal guarantee) {
        this.approvedAscending = approvedAscending;
        this.guarantee = guarantee;
    }

    @Override
    public BigDecimal apply(LocalDate d) {
        BigDecimal declared = null;
        for (RateDeclaration r : approvedAscending) {
            if (r.getEffectiveFrom().isAfter(d)) {
                break;
            }
            declared = r.getRatePercent();
        }
        // A later version with a lower guarantee never lowers an earlier customer's floor: the
        // guarantee here is the account's OWN pinned version's.
        return declared == null ? guarantee : declared.max(guarantee);
    }
}
