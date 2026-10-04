package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;

/** One priced life: its age on the quote date, what the plan pays for it, and its yearly premium. */
public record FuneralQuoteLine(FuneralRole role, String name, int age, BigDecimal benefit, BigDecimal yearlyPremium) {}
