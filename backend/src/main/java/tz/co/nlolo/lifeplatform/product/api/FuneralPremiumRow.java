package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;

/** The fixed yearly premium for one life in one role, on one plan, at ages {@code ageFrom..ageTo} inclusive. */
public record FuneralPremiumRow(String planCode, FuneralRole role, int ageFrom, int ageTo, BigDecimal yearlyPremium) {}
