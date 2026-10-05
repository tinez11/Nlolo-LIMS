package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * A UNIT_LINKED version's terms (product step 6, U1; product V25): which register funds it offers, how much of
 * each premium buys units in each policy year, the monthly fee, the mortality table the cost of insurance is
 * charged from, what death pays, when non-payment lapses, the surrender and premium floors, and the sum-assured
 * multiples. Every rule the unit engine applies is here as data. {@link #none()} on every other version.
 *
 * <p>{@code options} carries U2's terms (product V26, plan R2) -- switching, withdrawals, top-ups and the surrender
 * charge -- and is {@link UnitLinkedOptions#none()} on every version published before them.
 */
public record UnitLinkedPlan(boolean unitLinked, List<String> fundCodes, List<AllocationBand> allocationBands,
                             BigDecimal monthlyPolicyFee, MortalityBasis mortalityBasis, List<MortalityRow> mortality,
                             DeathRule deathRule, LapseRule lapseRule, Integer minimumPremiumYears,
                             int minimumSurrenderYears, int lowFundWarningMonths, List<PremiumMinimum> premiumMinimums,
                             BigDecimal sumAssuredMultipleMin, BigDecimal sumAssuredMultipleMax,
                             UnitLinkedOptions options) {

    /** What death pays (spec Q5): the higher of sum assured and fund value, or both. */
    public enum DeathRule { HIGHER_OF, SUM_ASSURED_PLUS_FUND }

    /** When the policy lapses (spec Q6): when the fund can no longer meet its charges, or on non-payment. */
    public enum LapseRule { EXHAUSTION, NON_PAYMENT }

    public enum MortalityBasis { UNISEX, BY_SEX }

    /** Policy years {@code fromYear} to {@code toYear} (null = onwards): this percent of each premium buys units. */
    public record AllocationBand(int fromYear, Integer toYear, BigDecimal percent) {}

    /** Ages {@code ageFrom} to {@code ageTo} (null = onwards); {@code sex} null on a UNISEX table. */
    public record MortalityRow(int ageFrom, Integer ageTo, String sex, BigDecimal annualRatePerMille) {}

    /** The least premium accepted at this frequency; a frequency with no row is not offered. */
    public record PremiumMinimum(String frequency, BigDecimal amount) {}

    public UnitLinkedPlan {
        fundCodes = fundCodes == null ? List.of() : List.copyOf(fundCodes);
        allocationBands = allocationBands == null ? List.of() : List.copyOf(allocationBands);
        mortality = mortality == null ? List.of() : List.copyOf(mortality);
        premiumMinimums = premiumMinimums == null ? List.of() : List.copyOf(premiumMinimums);
        options = options == null ? UnitLinkedOptions.none() : options;
    }

    /** Authored terms; {@code unitLinked} is true. */
    public static UnitLinkedPlan of(List<String> fundCodes, List<AllocationBand> allocationBands, BigDecimal monthlyPolicyFee,
                                    MortalityBasis mortalityBasis, List<MortalityRow> mortality, DeathRule deathRule,
                                    LapseRule lapseRule, Integer minimumPremiumYears, int minimumSurrenderYears,
                                    int lowFundWarningMonths, List<PremiumMinimum> premiumMinimums,
                                    BigDecimal sumAssuredMultipleMin, BigDecimal sumAssuredMultipleMax) {
        return new UnitLinkedPlan(true, fundCodes, allocationBands, monthlyPolicyFee, mortalityBasis, mortality, deathRule,
            lapseRule == null ? LapseRule.EXHAUSTION : lapseRule, minimumPremiumYears, minimumSurrenderYears,
            lowFundWarningMonths, premiumMinimums, sumAssuredMultipleMin, sumAssuredMultipleMax, UnitLinkedOptions.none());
    }

    /** The same terms with U2's options (plan R2): of(...) keeps its U1 signature for every existing caller. */
    public UnitLinkedPlan withOptions(UnitLinkedOptions options) {
        return new UnitLinkedPlan(unitLinked, fundCodes, allocationBands, monthlyPolicyFee, mortalityBasis, mortality,
            deathRule, lapseRule, minimumPremiumYears, minimumSurrenderYears, lowFundWarningMonths, premiumMinimums,
            sumAssuredMultipleMin, sumAssuredMultipleMax, options);
    }

    public static UnitLinkedPlan none() {
        return new UnitLinkedPlan(false, List.of(), List.of(), null, null, List.of(), null, null, null, 0, 0, List.of(),
            null, null, UnitLinkedOptions.none());
    }

    public boolean offersFund(String code) {
        return fundCodes.contains(code);
    }

    /** The allocation percent for policy year {@code policyYear} (1 = the first year). */
    public BigDecimal allocationPercent(int policyYear) {
        return allocationBands.stream()
            .filter(b -> policyYear >= b.fromYear() && (b.toYear() == null || policyYear <= b.toYear()))
            .map(AllocationBand::percent).findFirst()
            .orElseThrow(() -> new IllegalStateException("No allocation band covers policy year " + policyYear));
    }

    /**
     * The annual rate per 1,000 of risk at this attained age. On a BY_SEX table a null sex refuses rather than
     * guesses -- the same rule pricing applies to an unrecorded sex.
     */
    public BigDecimal annualRatePerMille(int age, String sex) {
        if (mortalityBasis == MortalityBasis.BY_SEX && (sex == null || sex.isBlank())) {
            throw new IllegalStateException("This version's mortality table is by sex and no sex is recorded for the life");
        }
        String wanted = mortalityBasis == MortalityBasis.BY_SEX ? sex : null;
        return mortality.stream()
            .filter(r -> java.util.Objects.equals(r.sex(), wanted))
            .filter(r -> age >= r.ageFrom() && (r.ageTo() == null || age <= r.ageTo()))
            .map(MortalityRow::annualRatePerMille).findFirst()
            .orElseThrow(() -> new IllegalStateException("No mortality band covers age " + age
                + (wanted != null ? " (" + wanted + ")" : "")));
    }

    public Optional<BigDecimal> minimumPremium(String frequency) {
        return premiumMinimums.stream().filter(m -> m.frequency().equals(frequency)).map(PremiumMinimum::amount).findFirst();
    }
}
