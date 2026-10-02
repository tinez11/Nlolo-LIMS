package tz.co.nlolo.lifeplatform.product.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import tz.co.nlolo.lifeplatform.product.api.DepositPlan;
import tz.co.nlolo.lifeplatform.product.api.DepositRateRow;

import java.math.BigDecimal;
import java.util.List;

/** A fixed-term deposit's grid on the wire. Absent means the version is not a deposit. */
public record DepositRequest(@NotEmpty @Valid List<Rate> rates) {

    public record Rate(@NotNull BigDecimal minAmount, @NotNull Integer termMonths, @NotNull BigDecimal ratePercent) {}

    public DepositPlan toPlan() {
        return new DepositPlan(rates.stream().map(r -> new DepositRateRow(r.minAmount(), r.termMonths(), r.ratePercent())).toList());
    }
}
