package tz.co.nlolo.lifeplatform.product;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.product.api.FundDirectory;
import tz.co.nlolo.lifeplatform.product.api.InvalidProductVersionException;
import tz.co.nlolo.lifeplatform.product.api.ProductCategory;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedOptions;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedOptions.SurrenderChargeBand;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedPlan;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedPlan.AllocationBand;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedPlan.DeathRule;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedPlan.LapseRule;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedPlan.MortalityBasis;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedPlan.MortalityRow;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedPlan.PremiumMinimum;
import tz.co.nlolo.lifeplatform.product.domain.UnitLinkedPlanValidator;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Every refusal of a UNIT_LINKED version's terms, in the words the console mirrors (spec §4). */
class UnitLinkedPlanValidatorTest {

    private static final FundDirectory REGISTER = code -> Optional.ofNullable(Map.of(
        "EQ1", new FundDirectory.FundSummary("EQ1", "TZS", true),
        "BD1", new FundDirectory.FundSummary("BD1", "TZS", true),
        "OLD", new FundDirectory.FundSummary("OLD", "TZS", false),
        "USD1", new FundDirectory.FundSummary("USD1", "USD", true)).get(code));

    private static BigDecimal d(String v) { return new BigDecimal(v); }

    private static final List<AllocationBand> BANDS = List.of(new AllocationBand(1, 2, d("90")), new AllocationBand(3, null, d("98")));
    private static final List<MortalityRow> UNISEX = List.of(new MortalityRow(18, 39, null, d("1.2")),
        new MortalityRow(40, 59, null, d("4.5")), new MortalityRow(60, null, null, d("25")));
    private static final List<PremiumMinimum> MINIMUMS = List.of(new PremiumMinimum("MONTHLY", d("50000")));

    static UnitLinkedPlan plan(List<String> funds, List<AllocationBand> bands, MortalityBasis basis, List<MortalityRow> mortality,
                               List<PremiumMinimum> minimums, String multipleMin, String multipleMax) {
        return UnitLinkedPlan.of(funds, bands, d("2000"), basis, mortality, DeathRule.HIGHER_OF, LapseRule.EXHAUSTION,
            null, 0, 3, minimums, d(multipleMin), d(multipleMax));
    }

    static UnitLinkedPlan valid() {
        return plan(List.of("EQ1", "BD1"), BANDS, MortalityBasis.UNISEX, UNISEX, MINIMUMS, "5", "20");
    }

    private static void check(UnitLinkedPlan plan) {
        UnitLinkedPlanValidator.validate(ProductCategory.UNIT_LINKED, plan, "TZS", 18, REGISTER, false);
    }

    private static void refused(UnitLinkedPlan plan, String message) {
        assertThatThrownBy(() -> check(plan)).isInstanceOf(InvalidProductVersionException.class).hasMessageContaining(message);
    }

    @Test
    void aCompleteVersionIsAccepted() {
        assertThatCode(() -> check(valid())).doesNotThrowAnyException();
    }

    @Test
    void termsOnlyOnAUnitLinkedProductAndAlwaysOnOne() {
        assertThatThrownBy(() -> UnitLinkedPlanValidator.validate(ProductCategory.TERM_LIFE, valid(), "TZS", 18, REGISTER, false))
            .hasMessage("Unit-linked terms are only valid on a UNIT_LINKED product");
        assertThatCode(() -> UnitLinkedPlanValidator.validate(ProductCategory.TERM_LIFE, UnitLinkedPlan.none(), "TZS", 18, REGISTER, false))
            .doesNotThrowAnyException();
        refused(UnitLinkedPlan.none(), "A UNIT_LINKED version needs unit-linked terms");
    }

    @Test
    void aUnitLinkedVersionIsNotRatedByBaseRatesOrFactors() {
        assertThatThrownBy(() -> UnitLinkedPlanValidator.validate(ProductCategory.UNIT_LINKED, valid(), "TZS", 18, REGISTER, true))
            .hasMessageContaining("is not priced by base rates or rating factors");
    }

    @Test
    void fundsComeFromTheRegisterOpenAndInTheProductsCurrency() {
        refused(plan(List.of(), BANDS, MortalityBasis.UNISEX, UNISEX, MINIMUMS, "5", "20"), "offers at least one fund");
        refused(plan(List.of("EQ1", "EQ1"), BANDS, MortalityBasis.UNISEX, UNISEX, MINIMUMS, "5", "20"), "Fund EQ1 is offered twice");
        refused(plan(List.of("ABC"), BANDS, MortalityBasis.UNISEX, UNISEX, MINIMUMS, "5", "20"), "Fund ABC is not in the fund register");
        refused(plan(List.of("OLD"), BANDS, MortalityBasis.UNISEX, UNISEX, MINIMUMS, "5", "20"), "Fund OLD is closed");
        refused(plan(List.of("USD1"), BANDS, MortalityBasis.UNISEX, UNISEX, MINIMUMS, "5", "20"), "is priced in USD; this product is TZS");
        assertThatThrownBy(() -> UnitLinkedPlanValidator.validate(ProductCategory.UNIT_LINKED, valid(), "TZS", 18, null, false))
            .hasMessage("No fund register is available to check the funds against");
    }

    @Test
    void allocationBandsRunOnFromYearOneAndTheLastIsOpen() {
        refused(plan(List.of("EQ1"), List.of(), MortalityBasis.UNISEX, UNISEX, MINIMUMS, "5", "20"), "at least one allocation band");
        refused(plan(List.of("EQ1"), List.of(new AllocationBand(2, null, d("95"))), MortalityBasis.UNISEX, UNISEX, MINIMUMS, "5", "20"),
            "band 1 starts at year 2");
        refused(plan(List.of("EQ1"), List.of(new AllocationBand(1, 2, d("90")), new AllocationBand(4, null, d("98"))),
            MortalityBasis.UNISEX, UNISEX, MINIMUMS, "5", "20"), "without gaps; band 2 starts at year 4");
        refused(plan(List.of("EQ1"), List.of(new AllocationBand(1, 2, d("90")), new AllocationBand(3, 9, d("98"))),
            MortalityBasis.UNISEX, UNISEX, MINIMUMS, "5", "20"), "The last allocation band must be open-ended");
        refused(plan(List.of("EQ1"), List.of(new AllocationBand(1, null, d("101"))), MortalityBasis.UNISEX, UNISEX, MINIMUMS, "5", "20"),
            "greater than 0 and at most 100");
    }

    @Test
    void theMortalityTableStartsAtTheEntryAgeRunsOnAndIsOpenEnded() {
        refused(plan(List.of("EQ1"), BANDS, MortalityBasis.UNISEX, List.of(), MINIMUMS, "5", "20"), "needs a mortality table");
        refused(plan(List.of("EQ1"), BANDS, MortalityBasis.UNISEX, List.of(new MortalityRow(25, null, null, d("2"))), MINIMUMS, "5", "20"),
            "must start at the minimum entry age 18; it starts at 25");
        refused(plan(List.of("EQ1"), BANDS, MortalityBasis.UNISEX, List.of(new MortalityRow(18, 39, null, d("2")),
            new MortalityRow(41, null, null, d("5"))), MINIMUMS, "5", "20"), "age 39 is followed by 41");
        refused(plan(List.of("EQ1"), BANDS, MortalityBasis.UNISEX, List.of(new MortalityRow(18, 99, null, d("2"))), MINIMUMS, "5", "20"),
            "The last mortality band must be open-ended (a whole-of-life policy has no maximum age)");
        refused(plan(List.of("EQ1"), BANDS, MortalityBasis.UNISEX, List.of(new MortalityRow(18, null, "MALE", d("2"))), MINIMUMS, "5", "20"),
            "A UNISEX mortality table has no sex on its rows");
    }

    @Test
    void aBySexTableHasBothSexesForEveryBand() {
        List<MortalityRow> maleOnly = List.of(new MortalityRow(18, null, "MALE", d("2")));
        refused(plan(List.of("EQ1"), BANDS, MortalityBasis.BY_SEX, maleOnly, MINIMUMS, "5", "20"),
            "A BY_SEX mortality table needs FEMALE and MALE rows for every band");
        List<MortalityRow> both = List.of(new MortalityRow(18, null, "MALE", d("2")), new MortalityRow(18, null, "FEMALE", d("1.5")));
        assertThatCode(() -> check(plan(List.of("EQ1"), BANDS, MortalityBasis.BY_SEX, both, MINIMUMS, "5", "20")))
            .doesNotThrowAnyException();
        List<MortalityRow> femaleGap = List.of(new MortalityRow(18, null, "MALE", d("2")), new MortalityRow(20, null, "FEMALE", d("1.5")));
        refused(plan(List.of("EQ1"), BANDS, MortalityBasis.BY_SEX, femaleGap, MINIMUMS, "5", "20"),
            "The mortality table (FEMALE) must start at the minimum entry age 18");
    }

    @Test
    void premiumsHaveAMinimumPerFrequencyAndTheMultiplesAreOrdered() {
        refused(plan(List.of("EQ1"), BANDS, MortalityBasis.UNISEX, UNISEX, List.of(), "5", "20"), "a minimum premium for each frequency");
        refused(plan(List.of("EQ1"), BANDS, MortalityBasis.UNISEX, UNISEX,
            List.of(new PremiumMinimum("MONTHLY", d("1")), new PremiumMinimum("MONTHLY", d("2"))), "5", "20"),
            "The MONTHLY minimum premium is given twice");
        refused(plan(List.of("EQ1"), BANDS, MortalityBasis.UNISEX, UNISEX, List.of(new PremiumMinimum("WEEKLY", d("1"))), "5", "20"),
            "A premium frequency is one of");
        refused(plan(List.of("EQ1"), BANDS, MortalityBasis.UNISEX, UNISEX, MINIMUMS, "20", "5"),
            "maximum multiple 5 is below its minimum 20");
        assertThatCode(() -> check(plan(List.of("EQ1"), BANDS, MortalityBasis.UNISEX, UNISEX,
            List.of(new PremiumMinimum("SINGLE", d("1000000")), new PremiumMinimum("MONTHLY", d("50000"))), "5", "20")))
            .doesNotThrowAnyException();
    }

    @Test
    void thePlanReadsTheRightBandAndRefusesAnUnrecordedSexOnABySexTable() {
        UnitLinkedPlan plan = valid();
        assertThat(plan.allocationPercent(1)).isEqualByComparingTo("90");
        assertThat(plan.allocationPercent(3)).isEqualByComparingTo("98");
        assertThat(plan.allocationPercent(40)).isEqualByComparingTo("98");
        assertThat(plan.annualRatePerMille(97, null)).isEqualByComparingTo("25");
        UnitLinkedPlan bySex = plan(List.of("EQ1"), BANDS, MortalityBasis.BY_SEX,
            List.of(new MortalityRow(18, null, "MALE", d("2")), new MortalityRow(18, null, "FEMALE", d("1.5"))), MINIMUMS, "5", "20");
        assertThat(bySex.annualRatePerMille(30, "FEMALE")).isEqualByComparingTo("1.5");
        assertThatThrownBy(() -> bySex.annualRatePerMille(30, null)).hasMessageContaining("no sex is recorded");
        assertThat(plan.lapseRule()).isEqualTo(LapseRule.EXHAUSTION);
    }

    // ---- U2 (product V26): switching, withdrawals, top-ups and the surrender charge ----

    /** 2 free switches then 5,000; withdrawals from 100,000 leaving 500,000; top-ups at 98% from 50,000; 10%, 5%, 0%. */
    static UnitLinkedOptions options() {
        return new UnitLinkedOptions(2, d("5000"), d("100000"), d("500000"), false, d("98"), d("50000"), List.of(
            new SurrenderChargeBand(1, 1, d("10")), new SurrenderChargeBand(2, 5, d("5")), new SurrenderChargeBand(6, null, d("0"))));
    }

    private static UnitLinkedOptions with(Integer free, String fee, String topUp, String minTopUp, List<SurrenderChargeBand> bands) {
        return new UnitLinkedOptions(free, fee == null ? null : d(fee), d("100000"), d("500000"), false,
            topUp == null ? null : d(topUp), minTopUp == null ? null : d(minTopUp), bands);
    }

    @Test
    void completeU2TermsAreAcceptedAndAVersionWithoutThemOffersNothing() {
        assertThatCode(() -> check(valid().withOptions(options()))).doesNotThrowAnyException();
        UnitLinkedOptions none = valid().options();
        assertThat(none.switchingOffered() || none.withdrawalsOffered() || none.topUpsOffered()).isFalse();
        assertThat(none.surrenderChargePercent(1)).isEqualByComparingTo("0");
        assertThat(options().surrenderChargePercent(1)).isEqualByComparingTo("10");
        assertThat(options().surrenderChargePercent(5)).isEqualByComparingTo("5");
        assertThat(options().surrenderChargePercent(30)).isEqualByComparingTo("0");
    }

    @Test
    void eachFeatureNeedsBothOfItsTerms() {
        refused(valid().withOptions(with(null, "5000", "98", "50000", List.of())),
            "Switching needs both the free switches per year and the fee for each switch after them");
        refused(valid().withOptions(with(2, "5000", "98", null, List.of())),
            "Top-ups need both their allocation percent and the minimum top-up");
    }

    @Test
    void aTopUpAllocationIsAboveZeroAndAtMostAHundred() {
        refused(valid().withOptions(with(2, "5000", "0", "50000", List.of())),
            "A top-up allocation percent is greater than 0 and at most 100");
        refused(valid().withOptions(with(2, "5000", "101", "50000", List.of())),
            "A top-up allocation percent is greater than 0 and at most 100");
    }

    @Test
    void surrenderChargeBandsRunOnFromYearOneAndEndOpen() {
        refused(valid().withOptions(with(2, "5000", "98", "50000", List.of(new SurrenderChargeBand(2, null, d("5"))))),
            "Surrender charge bands must run on from year 1 without gaps; band 1 starts at year 2");
        refused(valid().withOptions(with(2, "5000", "98", "50000", List.of(new SurrenderChargeBand(1, 5, d("5"))))),
            "The last surrender charge band must be open-ended");
        refused(valid().withOptions(with(2, "5000", "98", "50000", List.of(new SurrenderChargeBand(1, null, d("101"))))),
            "A surrender charge is between 0% and 100%");
    }
}
