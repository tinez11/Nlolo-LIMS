package tz.co.nlolo.lifeplatform.reinsurance.infrastructure;

import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyStatus;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyType;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyView;

import java.time.LocalDate;
import java.util.UUID;

/** {@code cessionPercent} is a decimal STRING on the wire, never a JSON number: it multiplies
 * money exactly as a commission rate does, docs/06-database-schema.md:32 forbids floats "anywhere
 * in the stack, wire format or storage", and M6 fixed this exact class of defect for
 * DisabilityClaimDetails.impairmentPercent -- which openApi().isValid() provably does not catch. */
public record TreatyResponseDto(UUID treatyId, String reinsurerName, TreatyType treatyType, TreatyStatus status,
                                 MoneyDto retentionLimit, String cessionPercent,
                                 LocalDate effectiveFrom, LocalDate effectiveTo,
                                 String commissionPercent, MoneyDto xolAnnualPremium) {

    public static TreatyResponseDto from(TreatyView view) {
        return new TreatyResponseDto(view.treatyId(), view.reinsurerName(), view.treatyType(), view.status(),
            new MoneyDto(view.retentionLimitAmount().toPlainString(), view.retentionLimitCurrency()),
            view.cessionPercent() == null ? null : view.cessionPercent().toPlainString(),
            view.effectiveFrom(), view.effectiveTo(),
            // IFRS 17 I3c: the commission not contingent on claims (K-02), and an XOL treaty's flat annual premium.
            view.commissionPercent().toPlainString(),
            view.xolAnnualPremium() == null ? null
                : new MoneyDto(view.xolAnnualPremium().toPlainString(), view.retentionLimitCurrency()));
    }
}
