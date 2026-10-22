package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A version's account terms (product step 3). {@link #none()} is a SCALE version: the step 1
 * behaviour, and what every version published before this step is.
 *
 * @param guaranteedRatePercent the effective annual floor; a declared rate never credits below it
 * @param minimumBalance a partial withdrawal may not leave less than this. NOT a lapse threshold --
 *     an account lapses only when its charges reach zero.
 * @param minRatePercent a term account's band (2026-10-21): the lowest rate a case may name. Null,
 *     with {@code maxRatePercent}, on every other account -- see {@link #isTermAccount()}.
 * @param maxRatePercent the highest rate a case may name.
 * @param accountChargeIds the Account charges the version takes (product V34, the user, 2026-10-22). When there are any,
 *     they are what its policies are charged by, and {@code charges} is one zero row for every year.
 */
public record AccumulationPlan(ValueBasis basis, BigDecimal guaranteedRatePercent, BigDecimal minimumBalance,
                               List<AccumulationChargeRow> charges, BigDecimal minRatePercent,
                               BigDecimal maxRatePercent, List<UUID> accountChargeIds) {

    public AccumulationPlan {
        basis = basis != null ? basis : ValueBasis.SCALE;
        charges = charges != null ? List.copyOf(charges) : List.of();
        accountChargeIds = accountChargeIds != null ? List.copyOf(accountChargeIds) : List.of();
    }

    /** An account on its own charge rows, as every version before V34 was. */
    public AccumulationPlan(ValueBasis basis, BigDecimal guaranteedRatePercent, BigDecimal minimumBalance,
                            List<AccumulationChargeRow> charges, BigDecimal minRatePercent, BigDecimal maxRatePercent) {
        this(basis, guaranteedRatePercent, minimumBalance, charges, minRatePercent, maxRatePercent, List.of());
    }

    /** An ordinary account, with no rate band -- every account version before the term account. */
    public AccumulationPlan(ValueBasis basis, BigDecimal guaranteedRatePercent, BigDecimal minimumBalance,
                            List<AccumulationChargeRow> charges) {
        this(basis, guaranteedRatePercent, minimumBalance, charges, null, null);
    }

    public static AccumulationPlan none() {
        return new AccumulationPlan(ValueBasis.SCALE, null, null, List.of());
    }

    /**
     * The account terms behind a fixed-term deposit. The deposit's rate comes from its grid, not
     * from here, so this is an account that charges nothing and guarantees nothing.
     */
    public static AccumulationPlan forDeposit() {
        return new AccumulationPlan(ValueBasis.ACCOUNT, BigDecimal.ZERO, BigDecimal.ZERO, List.of(
            new AccumulationChargeRow(1, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)));
    }

    /** These terms with one zero charge row for every policy year -- an account that charges nothing. */
    public AccumulationPlan withoutCharges() {
        return new AccumulationPlan(basis, guaranteedRatePercent, minimumBalance,
            List.of(new AccumulationChargeRow(1, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)),
            minRatePercent, maxRatePercent, accountChargeIds);
    }

    /** These terms charged by Account charges instead of rows of their own. */
    public AccumulationPlan withAccountCharges(List<UUID> chargeIds) {
        return new AccumulationPlan(basis, guaranteedRatePercent, minimumBalance, charges, minRatePercent, maxRatePercent,
            chargeIds);
    }

    public boolean isAccount() { return basis == ValueBasis.ACCOUNT; }

    /**
     * A term account (2026-10-21): an account whose rate each policy locks at sale from this band,
     * and which pays nothing out until it matures. The band's presence is the whole difference --
     * the way a deposit grid's presence is a fixed-term deposit's.
     */
    public boolean isTermAccount() { return isAccount() && minRatePercent != null && maxRatePercent != null; }

    /** Whether a case's named rate is one this band offers. */
    public boolean offers(BigDecimal ratePercent) {
        return isTermAccount() && ratePercent != null
            && ratePercent.compareTo(minRatePercent) >= 0 && ratePercent.compareTo(maxRatePercent) <= 0;
    }

    /** The charge row for a policy year. The validator guarantees one exists for every year >= 1. */
    public AccumulationChargeRow chargesFor(int policyYear) {
        return charges.stream().filter(r -> r.covers(policyYear)).findFirst()
            .orElseThrow(() -> new IllegalStateException("No account charge row covers policy year " + policyYear));
    }
}
