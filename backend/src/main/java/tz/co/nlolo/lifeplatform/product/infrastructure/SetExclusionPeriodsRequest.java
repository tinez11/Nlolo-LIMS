package tz.co.nlolo.lifeplatform.product.infrastructure;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * How long each policy-term exclusion stays open, in months from cover start.
 *
 * <p>Both fields are nullable and null CLEARS that window — a product with no such exclusion is
 * the normal case and has to stay expressible. That is also why neither is {@code @NotNull}: a
 * body of {@code {}} is a legitimate request meaning "this product excludes neither", not a
 * malformed one.
 *
 * <p>The upper bound is 120 months rather than unbounded. A window longer than a decade is not a
 * policy term, it is a typo, and the cost of accepting one is a claim declined years after any
 * assessor would defend the decision.
 */
public record SetExclusionPeriodsRequest(
    @Min(0) @Max(120) Integer suicideExclusionMonths,
    @Min(0) @Max(120) Integer preExistingExclusionMonths) {}
