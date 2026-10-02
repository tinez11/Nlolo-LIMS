package tz.co.nlolo.lifeplatform.policy.api;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * @param bonusSurrenderValueAmount what attached bonuses add (product step 4), already INCLUDED in
 *                                  quotedValueAmount; zero for a version that is not with-profits
 *                                  or whose bonus surrender basis is NONE
 */
public record SurrenderQuoteView(String policyNumber, BigDecimal quotedValueAmount, String quotedValueCurrency, Instant quotedAt,
                                 BigDecimal bonusSurrenderValueAmount) {}
