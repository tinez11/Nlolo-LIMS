package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * A UNIT_LINKED version's U2 terms (product V26, plan R1): fund switches, partial withdrawals, top-ups and the
 * surrender charge. Each feature is offered only when its own terms are present; {@link #none()} -- every version
 * published before U2 -- offers none of them and charges nothing.
 *
 * @param freeSwitchesPerYear switches per policy year with no fee; null = switching is not offered
 * @param switchFee the flat fee for each switch beyond the free ones
 * @param minimumWithdrawal the smallest withdrawal; null = withdrawals are not offered
 * @param minimumRemainingValue the value that must stay in the policy after a withdrawal, at the latest prices
 * @param withdrawalReducesSumAssured whether a withdrawal cuts the sum assured by the gross amount (spec Q4)
 * @param topUpAllocationPercent the share of a top-up that buys units; null = top-ups are not offered
 * @param minimumTopUp the smallest top-up
 * @param surrenderCharges the charge by policy year on surrenders, withdrawals and non-payment lapses
 */
public record UnitLinkedOptions(Integer freeSwitchesPerYear, BigDecimal switchFee, BigDecimal minimumWithdrawal,
                                BigDecimal minimumRemainingValue, boolean withdrawalReducesSumAssured,
                                BigDecimal topUpAllocationPercent, BigDecimal minimumTopUp,
                                List<SurrenderChargeBand> surrenderCharges) {

    /** Policy years {@code fromYear} to {@code toYear} (null = onwards) charge {@code percent} of the value sold. */
    public record SurrenderChargeBand(int fromYear, Integer toYear, BigDecimal percent) {}

    public UnitLinkedOptions {
        surrenderCharges = surrenderCharges == null ? List.of() : List.copyOf(surrenderCharges);
    }

    public static UnitLinkedOptions none() {
        return new UnitLinkedOptions(null, null, null, null, false, null, null, List.of());
    }

    public boolean switchingOffered() { return freeSwitchesPerYear != null; }

    public boolean withdrawalsOffered() { return minimumWithdrawal != null; }

    public boolean topUpsOffered() { return topUpAllocationPercent != null; }

    /** Whether anything here was authored at all -- the store writes a row only then. */
    public boolean authored() {
        return switchingOffered() || withdrawalsOffered() || topUpsOffered() || !surrenderCharges.isEmpty();
    }

    /** The charge for a sale in policy year {@code policyYear} (1 = the first); zero when no band covers it. */
    public BigDecimal surrenderChargePercent(int policyYear) {
        return surrenderCharges.stream()
            .filter(b -> policyYear >= b.fromYear() && (b.toYear() == null || policyYear <= b.toYear()))
            .map(SurrenderChargeBand::percent).findFirst().orElse(BigDecimal.ZERO);
    }
}
