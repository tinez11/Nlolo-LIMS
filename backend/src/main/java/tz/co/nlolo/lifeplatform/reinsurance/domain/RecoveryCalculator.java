package tz.co.nlolo.lifeplatform.reinsurance.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * BOTH FORMULAS BELOW ARE INVENTED PLACEHOLDERS, flagged rather than guessed silently -- see
 * {@link CessionCalculator}'s javadoc for why (the reinsurance aggregate design is a documentation
 * void on this platform). Needs Actuarial/Reinsurance sign-off.
 *
 * <p><b>Two distinct paths, because the two treaty families recover differently.</b> This is what
 * "XOL participates only in recovery" means concretely:
 * <ul>
 *   <li><b>Proportional</b> ({@link #proportional}) -- for QUOTA_SHARE and SURPLUS, which ceded
 *       risk at issuance. The reinsurer's share of a settled claim is the same fraction of the
 *       loss as it took of the sum assured. Requires a {@link Cession} to exist.</li>
 *   <li><b>Excess-of-loss</b> ({@link #excessOfLoss}) -- for XOL, which ceded nothing at issuance
 *       and therefore has NO cession to be proportional to. The reinsurer covers the part of the
 *       loss above the treaty's retention. Requires no cession by construction, which is exactly
 *       why XOL produces none.</li>
 * </ul>
 *
 * <p>Both return empty on a currency mismatch rather than converting -- no FX table exists on this
 * platform -- and both return empty for a zero result, because V2's {@code recoverable_amount > 0}
 * CHECK would reject a zero row and "recovered nothing" is not a financial record.
 */
public final class RecoveryCalculator {

    private RecoveryCalculator() {}

    /**
     * {@code settledAmount x (cededAmount / sumAssured)}, capped at the cession's own {@code
     * cededAmount}. Proportional recovery is the ordinary treaty convention, but no document on
     * this platform states it -- flagged.
     *
     * <p><b>Capped, deliberately (I3, final review).</b> {@code claims.Claim.approve} does not cap
     * {@code approvedAmount} at the policy's sum assured, so a settled amount CAN legitimately
     * exceed the sum assured this formula divides by. Without the cap, that produces a recoverable
     * strictly greater than {@code cededAmount} -- the reinsurer would be booked for more than the
     * risk it actually accepted under the cession, which is never correct: a reinsurer's exposure
     * on a proportional treaty cannot exceed the risk it agreed to cover.
     *
     * @return the reinsurer's share (never more than {@code cession.getCededAmount()}), or empty on
     *         a currency mismatch or a zero result
     */
    public static Optional<BigDecimal> proportional(Cession cession, BigDecimal sumAssured,
                                                     BigDecimal settledAmount, String settledCurrency) {
        if (!cession.getCededCurrency().equals(settledCurrency)) {
            return Optional.empty();
        }
        if (sumAssured == null || sumAssured.signum() <= 0) {
            return Optional.empty();   // guards the divide; a non-positive sum assured cannot occur
        }                              // per V2's CHECK, but dividing by it would be unrecoverable
        BigDecimal recoverable = settledAmount
            .multiply(cession.getCededAmount())
            .divide(sumAssured, 2, RoundingMode.HALF_UP)
            .min(cession.getCededAmount());
        return recoverable.signum() > 0 ? Optional.of(recoverable) : Optional.empty();
    }

    /**
     * {@code max(0, settledAmount - retentionLimit)}.
     *
     * <p><b>Treaty capacity/layer limits are NOT modelled (I3, final review, the other half).</b> A
     * real XOL treaty is "N excess of M" -- a layer with a finite upper limit, not unlimited cover
     * above retention -- and a real SURPLUS treaty has a finite line capacity. Neither concept
     * exists on {@link ReinsuranceTreaty} (no capacity/limit column) or is enforced here: this
     * method computes the excess over retention with NO upper bound, so an XOL treaty is
     * effectively unlimited cover above retention as implemented, and a SURPLUS treaty's cession
     * (see {@code CessionCalculator}) is likewise not capped against any modelled capacity. This is
     * a missing concept, not a bug in what exists -- the formula above is exactly what IS
     * implemented; it never claims to model a layer limit. See the design spec's §8 for the
     * recorded deferral: this needs an actual capacity/limit column and a business rule for what
     * happens above it, neither of which any document on this platform defines, so nothing is
     * invented here to fill that gap.
     *
     * @return the excess over retention, or empty when the loss falls entirely within retention
     *         (the ordinary case) or the currency does not match
     */
    public static Optional<BigDecimal> excessOfLoss(ReinsuranceTreaty treaty,
                                                     BigDecimal settledAmount, String settledCurrency) {
        if (!treaty.getRetentionLimitCurrency().equals(settledCurrency)) {
            return Optional.empty();
        }
        BigDecimal recoverable = settledAmount
            .subtract(treaty.getRetentionLimitAmount())
            .max(BigDecimal.ZERO)
            .setScale(2, RoundingMode.HALF_UP);
        return recoverable.signum() > 0 ? Optional.of(recoverable) : Optional.empty();
    }
}
