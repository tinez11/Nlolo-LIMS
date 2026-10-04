package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;

/**
 * A payment frequency an annuity version offers, and what paying that often costs (product step 5,
 * Q5): the instalment is annual income x factor / payments per year. ANNUAL is exactly 1.
 */
public record AnnuityFrequencyFactor(String frequency, BigDecimal factor) {}
