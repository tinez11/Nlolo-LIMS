package tz.co.nlolo.lifeplatform.annuity.api;

import java.math.BigDecimal;

/**
 * What a death claim on an annuity may pay (product step 5): the capital-protection refund on the last
 * death, or zero -- a verified death that pays nothing is the right outcome for a life-only annuity.
 */
public record DeathValue(BigDecimal capitalRefund, String currency) {}
