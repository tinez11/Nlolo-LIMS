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
     * {@code settledAmount x (cededAmount / sumAssured)}. Proportional recovery is the ordinary
     * treaty convention, but no document on this platform states it -- flagged.
     *
     * @return the reinsurer's share, or empty on a currency mismatch or a zero result
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
            .divide(sumAssured, 2, RoundingMode.HALF_UP);
        return recoverable.signum() > 0 ? Optional.of(recoverable) : Optional.empty();
    }

    /**
     * {@code max(0, settledAmount - retentionLimit)}.
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
