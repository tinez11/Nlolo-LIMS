package tz.co.nlolo.lifeplatform.reinsurance.infrastructure;

import tz.co.nlolo.lifeplatform.reinsurance.api.CessionView;

import java.util.UUID;

/** {@code cededPremium} is a nullable {@link MoneyDto}: {@code CessionView}'s javadoc records that
 * {@code cededPremiumAmount}/{@code cededPremiumCurrency} are non-null together or null together
 * (V2's {@code cession_ceded_premium_paired}), null for a treaty type that cedes risk without a
 * modelled premium share. */
public record CessionResponseDto(UUID cessionId, String policyNumber, UUID treatyId,
                                  MoneyDto cededAmount, MoneyDto cededPremium) {

    public static CessionResponseDto from(CessionView view) {
        return new CessionResponseDto(view.cessionId(), view.policyNumber(), view.treatyId(),
            new MoneyDto(view.cededAmount().toPlainString(), view.cededCurrency()),
            view.cededPremiumAmount() == null ? null
                : new MoneyDto(view.cededPremiumAmount().toPlainString(), view.cededPremiumCurrency()));
    }
}
