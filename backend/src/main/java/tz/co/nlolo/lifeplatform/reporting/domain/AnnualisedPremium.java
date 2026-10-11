package tz.co.nlolo.lifeplatform.reporting.domain;

import java.math.BigDecimal;
import java.util.Optional;

/** Premium x instalments per year (product.api.PremiumFrequency: MONTHLY 12, QUARTERLY 4, ANNUALLY 1).
 * SINGLE is reported separately and never blended in. An unrecognised frequency gives nothing rather than
 * a default of 12: a wrong multiplier on a live figure is worse than a visibly missing one. */
public final class AnnualisedPremium {
    private AnnualisedPremium() {}

    public static Optional<BigDecimal> of(BigDecimal premium, String frequency) {
        if (premium == null || frequency == null) return Optional.empty();
        return switch (frequency) {
            case "MONTHLY" -> Optional.of(premium.multiply(BigDecimal.valueOf(12)));
            case "QUARTERLY" -> Optional.of(premium.multiply(BigDecimal.valueOf(4)));
            case "ANNUALLY" -> Optional.of(premium);
            default -> Optional.empty();
        };
    }

    public static boolean isSingle(String frequency) {
        return "SINGLE".equals(frequency);
    }

    public static boolean isRecognised(String frequency) {
        return frequency != null && switch (frequency) {
            case "MONTHLY", "QUARTERLY", "ANNUALLY", "SINGLE" -> true;
            default -> false;
        };
    }
}
