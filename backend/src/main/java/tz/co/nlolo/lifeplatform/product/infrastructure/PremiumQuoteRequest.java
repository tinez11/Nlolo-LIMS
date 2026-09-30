package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.api.PremiumFrequency;
import tz.co.nlolo.lifeplatform.product.api.Sex;
import tz.co.nlolo.lifeplatform.product.api.SmokerStatus;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Mirrors api/openapi/openapi-product.yaml's PremiumQuoteRequest.
 *
 * Takes dateOfBirth, NOT a precomputed age: entry age is the input a mispriced
 * policy turns on, so it is derived where the rate table lives rather than
 * trusted from a caller. `asOf` is optional and defaults to today -- it selects
 * the product version, so a quote reproduced later on the same asOf prices on the
 * same rules.
 *
 * occupationClass and smokerStatus are ASSERTED here: no party record carries
 * either (see the M13 design spec's open items).
 */
public record PremiumQuoteRequest(
    @NotNull @Positive BigDecimal sumAssuredAmount,
    @NotNull String sumAssuredCurrency,
    @NotNull LocalDate dateOfBirth,
    @NotNull Sex sex,
    @NotNull SmokerStatus smokerStatus,
    @NotNull String occupationClass,
    @NotNull PremiumFrequency frequency,
    LocalDate asOf,
    // The policy term the quote is for (V16). Optional: null prices against unbanded rates only and
    // is refused on a term-banded version -- the same rule issuance follows.
    @Positive Integer policyTermMonths) {}
