package tz.co.nlolo.lifeplatform.accumulation.api;

import java.math.BigDecimal;

/**
 * An account as at a death (product step 3, spec §10.6): what it held, with interest to the day,
 * before anything dated after the death. {@code premiumsBeforeDeath} is the base for a
 * percentage-of-premiums floor; {@code contributionsAfterDeath} is owed to the estate on top.
 */
public record DeathValuation(BigDecimal accountValue, BigDecimal premiumsBeforeDeath,
                             BigDecimal contributionsAfterDeath, String currency) {}
