package tz.co.nlolo.lifeplatform.reinsurance.infrastructure;

import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyUtilisationView;

import java.util.UUID;

/**
 * How much has been ceded to one treaty.
 *
 * <p>A count and two totals, and deliberately NOT a percentage. Retention is what the cedant
 * keeps per life, not a cap on the treaty, so dividing ceded amount by it would produce a figure
 * that reads like capacity consumed and means nothing of the sort. No capacity or limit is
 * modelled anywhere on this platform, and inventing the denominator would be inventing the
 * number.
 *
 * <p>Money is the usual {@code {amount, currencyCode}} with a string amount. Both totals are
 * zero rather than null for a treaty nobody has ceded to yet: that is an ordinary state for an
 * ACTIVE treaty, and a null on a money screen reads as a failure to load.
 */
public record TreatyUtilisationResponseDto(UUID treatyId, long cessionCount,
                                            MoneyDto cededAmount, MoneyDto cededPremium) {

    public static TreatyUtilisationResponseDto from(TreatyUtilisationView view) {
        return new TreatyUtilisationResponseDto(view.treatyId(), view.cessionCount(),
            new MoneyDto(view.cededAmount().toPlainString(), view.cededCurrency()),
            new MoneyDto(view.cededPremiumAmount().toPlainString(), view.cededPremiumCurrency()));
    }
}
