package tz.co.nlolo.lifeplatform.product.infrastructure;

import jakarta.validation.Valid;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedPlan;

import java.math.BigDecimal;
import java.util.List;

/**
 * A UNIT_LINKED version's terms on the wire. Nothing is @NotNull: UnitLinkedPlanValidator refuses an absence in
 * the words the console mirrors (FuneralRequest's reason). A missing whole number becomes -1, so a rule refuses
 * it in its own words rather than an NPE; a missing lapse rule is the default, EXHAUSTION (spec Q6).
 */
public record UnitLinkedRequest(List<String> fundCodes, @Valid List<Band> allocationBands, BigDecimal monthlyPolicyFee,
                                UnitLinkedPlan.MortalityBasis mortalityBasis, @Valid List<Mortality> mortality,
                                UnitLinkedPlan.DeathRule deathRule, UnitLinkedPlan.LapseRule lapseRule,
                                Integer minimumPremiumYears, Integer minimumSurrenderYears, Integer lowFundWarningMonths,
                                @Valid List<Minimum> premiumMinimums, BigDecimal sumAssuredMultipleMin,
                                BigDecimal sumAssuredMultipleMax) {

    public record Band(Integer fromYear, Integer toYear, BigDecimal percent) {}

    public record Mortality(Integer ageFrom, Integer ageTo, String sex, BigDecimal annualRatePerMille) {}

    public record Minimum(String frequency, BigDecimal amount) {}

    public UnitLinkedPlan toPlan() {
        return UnitLinkedPlan.of(
            fundCodes == null ? List.of() : fundCodes.stream().map(c -> c == null ? "" : c.trim().toUpperCase()).toList(),
            allocationBands == null ? List.of() : allocationBands.stream()
                .map(b -> new UnitLinkedPlan.AllocationBand(orMinusOne(b.fromYear()), b.toYear(), b.percent())).toList(),
            monthlyPolicyFee, mortalityBasis,
            mortality == null ? List.of() : mortality.stream()
                .map(m -> new UnitLinkedPlan.MortalityRow(orMinusOne(m.ageFrom()), m.ageTo(),
                    m.sex() == null || m.sex().isBlank() ? null : m.sex().trim().toUpperCase(), m.annualRatePerMille()))
                .toList(),
            deathRule, lapseRule, minimumPremiumYears, orMinusOne(minimumSurrenderYears), orMinusOne(lowFundWarningMonths),
            premiumMinimums == null ? List.of() : premiumMinimums.stream()
                .map(m -> new UnitLinkedPlan.PremiumMinimum(m.frequency(), m.amount())).toList(),
            sumAssuredMultipleMin, sumAssuredMultipleMax);
    }

    private static int orMinusOne(Integer value) {
        return value != null ? value : -1;
    }
}
