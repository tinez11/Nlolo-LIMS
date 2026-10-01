package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.api.Sex;
import tz.co.nlolo.lifeplatform.product.api.SmokerStatus;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * One base rate cell on the wire. `ageTo` is INCLUSIVE.
 *
 * `@Positive` on the rate duplicates the table's CHECK (rate_per_mille > 0) on
 * purpose: without it a zero or negative rate reaches Postgres and comes back as
 * a DataIntegrityViolationException, which surfaces as a 500 rather than a 400
 * naming the field. The CHECK stays because it makes the bad value
 * unrepresentable at rest, not merely unaccepted at the edge.
 */
public record BaseRateRequest(
    @NotNull @PositiveOrZero Integer ageFrom,
    @NotNull @PositiveOrZero Integer ageTo,
    @NotNull Sex sex,
    @NotNull SmokerStatus smokerStatus,
    @NotNull @Positive BigDecimal ratePerMille,
    // Optional term band (V16): both null = any term, both set = a term within [from, to]. The
    // application refuses one set without the other and any overlap; the shape CHECK backs it.
    @Positive Integer termFromMonths,
    @Positive Integer termToMonths) {}
