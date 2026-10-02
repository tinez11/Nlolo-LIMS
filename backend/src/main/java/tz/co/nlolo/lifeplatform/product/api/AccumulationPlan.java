package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * A version's account terms (product step 3). {@link #none()} is a SCALE version: the step 1
 * behaviour, and what every version published before this step is.
 *
 * @param guaranteedRatePercent the effective annual floor; a declared rate never credits below it
 * @param minimumBalance a partial withdrawal may not leave less than this. NOT a lapse threshold --
 *     an account lapses only when its charges reach zero.
 */
public record AccumulationPlan(ValueBasis basis, BigDecimal guaranteedRatePercent, BigDecimal minimumBalance,
                               List<AccumulationChargeRow> charges) {

    public AccumulationPlan {
        basis = basis != null ? basis : ValueBasis.SCALE;
        charges = charges != null ? List.copyOf(charges) : List.of();
    }

    public static AccumulationPlan none() {
        return new AccumulationPlan(ValueBasis.SCALE, null, null, List.of());
    }

    public boolean isAccount() { return basis == ValueBasis.ACCOUNT; }

    /** The charge row for a policy year. The validator guarantees one exists for every year >= 1. */
    public AccumulationChargeRow chargesFor(int policyYear) {
        return charges.stream().filter(r -> r.covers(policyYear)).findFirst()
            .orElseThrow(() -> new IllegalStateException("No account charge row covers policy year " + policyYear));
    }
}
