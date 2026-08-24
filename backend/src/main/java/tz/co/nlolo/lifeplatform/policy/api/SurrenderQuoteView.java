package tz.co.nlolo.lifeplatform.policy.api;

import java.math.BigDecimal;
import java.time.Instant;

public record SurrenderQuoteView(String policyNumber, BigDecimal quotedValueAmount, String quotedValueCurrency, Instant quotedAt) {}
