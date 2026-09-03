package tz.co.nlolo.lifeplatform.product.infrastructure;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import tz.co.nlolo.lifeplatform.product.api.EligibilityBounds;

import java.math.BigDecimal;

/**
 * The wire shape of a version's eligibility bounds, mirroring openapi-product.yaml's
 * {@code EligibilityBounds}.
 *
 * <p>Every field optional: Jakarta's value constraints treat null as valid, so declaring
 * {@code @Min}/{@code @Positive} here constrains a supplied value without making the
 * field mandatory. An unbounded dimension is a real product design.
 *
 * <p>The min ≤ max rule is NOT expressed here. Bean Validation has no clean cross-field
 * constraint without a custom validator, and the rule is already enforced twice where it
 * counts — in {@link EligibilityBounds}'s compact constructor and by the
 * {@code product_version_*_sane} CHECKs. A violation therefore arrives as a 400 from the
 * domain rather than as a field error, which is the right trade for a rule no sane caller
 * hits.
 */
public record EligibilityBoundsRequest(
    @Min(0) @Max(120) Integer minEntryAge,
    @Min(0) @Max(120) Integer maxEntryAge,
    @Positive Integer minTermMonths,
    @Positive Integer maxTermMonths,
    @Positive BigDecimal minSumAssured,
    @Positive BigDecimal maxSumAssured) {

    public EligibilityBounds toBounds() {
        return new EligibilityBounds(minEntryAge, maxEntryAge, minTermMonths, maxTermMonths,
            minSumAssured, maxSumAssured);
    }
}
