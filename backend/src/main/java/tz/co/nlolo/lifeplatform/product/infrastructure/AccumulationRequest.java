package tz.co.nlolo.lifeplatform.product.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import tz.co.nlolo.lifeplatform.product.api.AccumulationChargeRow;
import tz.co.nlolo.lifeplatform.product.api.AccumulationPlan;
import tz.co.nlolo.lifeplatform.product.api.ValueBasis;

import java.math.BigDecimal;
import java.util.List;

/** An ACCOUNT version's terms on the wire (product step 3). Absent on the request means a SCALE version. */
public record AccumulationRequest(@NotNull BigDecimal guaranteedRatePercent, @NotNull BigDecimal minimumBalance,
                                  @NotNull @Valid List<ChargeRow> charges) {

    public record ChargeRow(@NotNull Integer fromPolicyYear, Integer toPolicyYear,
                            @NotNull BigDecimal contributionAllocationPercent,
                            @NotNull BigDecimal transferAllocationPercent, @NotNull BigDecimal monthlyPolicyFee) {}

    public AccumulationPlan toPlan() {
        return new AccumulationPlan(ValueBasis.ACCOUNT, guaranteedRatePercent, minimumBalance,
            charges.stream().map(c -> new AccumulationChargeRow(c.fromPolicyYear(), c.toPolicyYear(),
                c.contributionAllocationPercent(), c.transferAllocationPercent(), c.monthlyPolicyFee())).toList());
    }
}
