package tz.co.nlolo.lifeplatform.reinsurance.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * How much has actually been ceded to one treaty.
 *
 * <p>The question a treaty exists to answer, and one this module could not. {@code TreatyView}
 * states what the treaty PROMISES -- a retention limit, a cession percent, a date range -- while
 * every cession naming it lived behind a per-policy lookup, so "how much have we ceded to this
 * reinsurer" had no query behind it at all.
 *
 * <p><b>Deliberately not a percentage of the retention limit.</b> Retention is what the CEDANT
 * keeps per life, not a cap on the treaty; dividing ceded amount by it would produce a
 * utilisation figure that reads like capacity consumed and means nothing of the sort. What the
 * platform can prove is the count and the totals, so that is what it states. A real capacity or
 * limit figure is not modelled anywhere on this platform, and inventing the denominator would be
 * inventing the number.
 *
 * <p>Zeroes, never nulls: an ACTIVE treaty nobody has ceded to yet is an ordinary state, and a
 * null on a money screen reads as a failure to load.
 */
public record TreatyUtilisationView(
    UUID treatyId,
    long cessionCount,
    BigDecimal cededAmount,
    String cededCurrency,
    BigDecimal cededPremiumAmount,
    String cededPremiumCurrency) {}
