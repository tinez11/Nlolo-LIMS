package tz.co.nlolo.lifeplatform.reinsurance.domain;

import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * THE ENTIRE ALGORITHM BELOW IS AN INVENTED PLACEHOLDER, flagged rather than guessed silently --
 * the same treatment M2 gave the underwriting decision engine and M7 gave commission.
 *
 * <p>No document on this platform defines how a treaty computes a cession.
 * {@code docs/03-aggregate-design.md:165} reads, in full, "### 7.4 `reinsurance` -- unchanged from
 * Rev 1", and Rev 1 is not in this repository. Every rule encoded here is M8's own decision and
 * needs Actuarial/Reinsurance sign-off:
 *
 * <ul>
 *   <li><b>QUOTA_SHARE cedes the same percentage of risk AND premium.</b> That is what a quota
 *       share means -- a fixed share of the business, premium travelling with the risk.</li>
 *   <li><b>SURPLUS cedes the excess over retention, and premium in proportion to the risk
 *       actually ceded.</b> A sum assured at or below retention cedes NOTHING, which is the
 *       correct and expected outcome for a small policy, not an error.</li>
 *   <li><b>XOL cedes nothing at issuance.</b> Excess-of-loss is a claim-level treaty. It
 *       participates only in recovery ({@link RecoveryCalculator}), where it recovers the excess
 *       of a loss over retention. Giving it an issuance interpretation would encode an
 *       actuarially wrong model, which for a reinsurance treaty surfaces as a financial
 *       misstatement rather than a bug.</li>
 *   <li><b>A currency mismatch cedes nothing rather than converting.</b> No FX table exists
 *       anywhere on this platform; inventing a rate is worse than not ceding. Only the RISK
 *       currency must match the treaty -- the ceded premium keeps the policy's own premium
 *       currency, since {@code policy.PolicyIssued} carries the two independently.</li>
 * </ul>
 */
public final class CessionCalculator {

    private CessionCalculator() {}

    /** What the caller should persist. Both premium fields are non-null together (V2's
     * {@code cession_ceded_premium_paired}), or both null when the ceded premium rounds to
     * zero -- V2's {@code cession_ceded_premium_positive} forbids persisting a zero premium row,
     * so callers (Task 6/7) must not assume {@code cededPremium()} is always populated. */
    public record CededAmounts(BigDecimal cededRisk, String riskCurrency,
                                BigDecimal cededPremium, String premiumCurrency) {}

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    /**
     * @return the amounts to cede, or empty when this treaty cedes nothing for this policy --
     *         which is a normal outcome (XOL, a sub-retention surplus risk, a currency mismatch),
     *         never an error. A zero result is deliberately empty rather than a zero row: V2's
     *         {@code ceded_amount > 0} CHECK would reject it, and "ceded nothing" is not a
     *         financial record.
     */
    public static Optional<CededAmounts> calculate(ReinsuranceTreaty treaty,
                                                    BigDecimal sumAssured, String sumAssuredCurrency,
                                                    BigDecimal premium, String premiumCurrency) {
        if (!treaty.getRetentionLimitCurrency().equals(sumAssuredCurrency)) {
            return Optional.empty();
        }
        BigDecimal cededRisk = switch (treaty.getTreatyType()) {
            case QUOTA_SHARE -> share(treaty.getCessionPercent(), sumAssured);
            case SURPLUS -> sumAssured.subtract(treaty.getRetentionLimitAmount()).max(BigDecimal.ZERO);
            case XOL -> BigDecimal.ZERO;
        };
        cededRisk = cededRisk.setScale(2, RoundingMode.HALF_UP);
        if (cededRisk.signum() <= 0) {
            return Optional.empty();
        }

        // Premium follows the risk. For a quota share that is the treaty's own percentage; for a
        // surplus it is the fraction of the sum assured actually ceded, which for a quota share
        // would give the identical answer -- expressed separately only because the quota-share
        // percentage is the treaty's stated term and should be applied as written.
        BigDecimal cededPremium = switch (treaty.getTreatyType()) {
            case QUOTA_SHARE -> share(treaty.getCessionPercent(), premium);
            case SURPLUS -> premium.multiply(cededRisk).divide(sumAssured, 2, RoundingMode.HALF_UP);
            case XOL -> BigDecimal.ZERO;   // unreachable: XOL returned empty above
        };
        cededPremium = cededPremium.setScale(2, RoundingMode.HALF_UP);

        // A ceded premium that rounds to zero must not be persisted -- V2's
        // cession_ceded_premium_positive would reject it -- but the ceded RISK is still real, so
        // record the cession with no premium share rather than dropping it entirely.
        BigDecimal premiumToRecord = cededPremium.signum() > 0 ? cededPremium : null;
        String premiumCurrencyToRecord = premiumToRecord == null ? null : premiumCurrency;

        return Optional.of(new CededAmounts(cededRisk, sumAssuredCurrency,
            premiumToRecord, premiumCurrencyToRecord));
    }

    private static BigDecimal share(BigDecimal percent, BigDecimal amount) {
        return amount.multiply(percent).divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);
    }
}
