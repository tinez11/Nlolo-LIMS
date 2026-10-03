package tz.co.nlolo.lifeplatform.product;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.product.api.*;
import tz.co.nlolo.lifeplatform.product.domain.AnnuityPlanValidator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AnnuityPlanValidator, rule by rule, in the exact words the console mirrors (product step 5). */
class AnnuityPlanValidatorTest {

    private static final EligibilityBounds AGES_60_TO_62 = new EligibilityBounds(60, 62, null, null, null, null);
    private static final CashValuePlan SCALE = new CashValuePlan("ACT/1", LocalDate.of(2026, 1, 1), "PROPORTIONATE", 2,
        List.of(new CashValueRowInput(2, null, null, new BigDecimal("200"), null)));
    private static final AccumulationPlan ACCOUNT = new AccumulationPlan(ValueBasis.ACCOUNT, BigDecimal.ONE, BigDecimal.ZERO,
        List.of(new AccumulationChargeRow(1, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)));
    private static final PayoutPlan MATURITY = PayoutPlan.authored(new PayoutTerms(15, null, null, null),
        List.of(new PayoutRowInput(PayoutKind.MATURITY, null, null, PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("100"), null)));
    private static final PayoutPlan FREE_LOOK_ONLY = PayoutPlan.authored(new PayoutTerms(15, null, null, null), List.of());

    private static AnnuityRateRow unisex(int age, String rate) {
        return new AnnuityRateRow(null, age, null, null, new BigDecimal(rate));
    }

    private static AnnuityRateRow joint(int age, int from, int to, String rate) {
        return new AnnuityRateRow(null, age, from, to, new BigDecimal(rate));
    }

    private static List<AnnuityRateRow> unisexAges() {
        return List.of(unisex(60, "72"), unisex(61, "74"), unisex(62, "76"));
    }

    private static AnnuityForm form(String code, int guarantee, boolean isJoint, String survivor, String escalation,
                                    boolean protectedCapital, AnnuityRateBasis basis, List<AnnuityRateRow> rates) {
        return new AnnuityForm(code, guarantee, isJoint, survivor == null ? null : new BigDecimal(survivor),
            new BigDecimal(escalation), protectedCapital, basis, rates);
    }

    private static AnnuityForm lifeOnly() {
        return form("LIFE-0G", 0, false, null, "0", false, AnnuityRateBasis.UNISEX, unisexAges());
    }

    /** Bands -2..-1, 0..3 at every age 60-62: covers the version's range -2..3 exactly. */
    private static AnnuityForm jointHalf() {
        List<AnnuityRateRow> rows = new ArrayList<>();
        for (int age = 60; age <= 62; age++) {
            rows.add(joint(age, -2, -1, "60"));
            rows.add(joint(age, 0, 3, "62"));
        }
        return form("JOINT-50", 0, true, "50", "0", false, AnnuityRateBasis.UNISEX, rows);
    }

    private static final List<AnnuityFrequencyFactor> FREQUENCIES = List.of(
        new AnnuityFrequencyFactor("MONTHLY", new BigDecimal("0.9800")), new AnnuityFrequencyFactor("ANNUAL", BigDecimal.ONE));

    private static AnnuityPlan plan(List<AnnuityForm> forms) {
        boolean anyJoint = forms.stream().anyMatch(AnnuityForm::joint);
        return new AnnuityPlan(true, AnnuityTiming.ARREARS, 12, anyJoint ? -2 : null, anyJoint ? 3 : null,
            "ACT/ANN/2026", LocalDate.of(2026, 1, 1), forms, FREQUENCIES);
    }

    private static AnnuityPlan withFrequencies(List<AnnuityFrequencyFactor> frequencies) {
        return new AnnuityPlan(true, AnnuityTiming.ARREARS, 12, null, null, "ACT/ANN/2026", LocalDate.of(2026, 1, 1),
            List.of(lifeOnly()), frequencies);
    }

    private static void validate(ProductCategory category, AnnuityPlan plan, EligibilityBounds bounds, CashValuePlan cashValue,
                                 AccumulationPlan accumulation, DepositPlan deposit, BonusPlan bonus, PayoutPlan payout) {
        AnnuityPlanValidator.validate(category, plan, bounds, cashValue, accumulation, deposit, bonus, payout);
    }

    private static void refused(AnnuityPlan plan, String message) {
        refused(ProductCategory.ANNUITY, plan, AGES_60_TO_62, CashValuePlan.none(), AccumulationPlan.none(),
            DepositPlan.none(), BonusPlan.none(), FREE_LOOK_ONLY, message);
    }

    private static void refused(ProductCategory category, AnnuityPlan plan, EligibilityBounds bounds, CashValuePlan cashValue,
                                AccumulationPlan accumulation, DepositPlan deposit, BonusPlan bonus, PayoutPlan payout,
                                String message) {
        assertThatThrownBy(() -> validate(category, plan, bounds, cashValue, accumulation, deposit, bonus, payout))
            .isInstanceOf(InvalidProductVersionException.class).hasMessage(message);
    }

    // ---- Accepted ----

    @Test
    void anOrdinaryVersionWithoutAnnuityTermsIsNotChecked() {
        assertThatCode(() -> validate(ProductCategory.TERM_LIFE, AnnuityPlan.none(), EligibilityBounds.none(), CashValuePlan.none(),
            AccumulationPlan.none(), DepositPlan.none(), BonusPlan.none(), PayoutPlan.none())).doesNotThrowAnyException();
    }

    @Test
    void aValidPlanWithEverySettingCombinedPasses() {
        AnnuityForm everything = form("JOINT-10G-3E-CP", 10, true, "66.6667", "3", true, AnnuityRateBasis.UNISEX,
            jointHalf().rates());
        assertThatCode(() -> validate(ProductCategory.ANNUITY, plan(List.of(lifeOnly(), jointHalf(), everything)),
            AGES_60_TO_62, CashValuePlan.none(), AccumulationPlan.none(), DepositPlan.none(), BonusPlan.none(), FREE_LOOK_ONLY))
            .doesNotThrowAnyException();
    }

    @Test
    void aBySexFormWithBothSexesAtEveryAgePasses() {
        List<AnnuityRateRow> rows = new ArrayList<>();
        for (int age = 60; age <= 62; age++) {
            rows.add(new AnnuityRateRow("FEMALE", age, null, null, new BigDecimal("70")));
            rows.add(new AnnuityRateRow("MALE", age, null, null, new BigDecimal("75")));
        }
        assertThatCode(() -> validate(ProductCategory.ANNUITY,
            plan(List.of(form("LIFE-BS", 0, false, null, "0", false, AnnuityRateBasis.BY_SEX, rows))),
            AGES_60_TO_62, CashValuePlan.none(), AccumulationPlan.none(), DepositPlan.none(), BonusPlan.none(), FREE_LOOK_ONLY))
            .doesNotThrowAnyException();
    }

    // ---- Category and exclusions ----

    @Test
    void annuityTermsOnlyOnAnnuity() {
        refused(ProductCategory.ENDOWMENT, plan(List.of(lifeOnly())), AGES_60_TO_62, CashValuePlan.none(), AccumulationPlan.none(),
            DepositPlan.none(), BonusPlan.none(), MATURITY, "Annuity terms are only for an ANNUITY product");
    }

    @Test
    void anAnnuityVersionMustHaveTerms() {
        refused(ProductCategory.ANNUITY, AnnuityPlan.none(), AGES_60_TO_62, CashValuePlan.none(), AccumulationPlan.none(),
            DepositPlan.none(), BonusPlan.none(), FREE_LOOK_ONLY,
            "An ANNUITY version must state its annuity terms: forms, rates and frequencies");
    }

    @Test
    void noCashValueTable() {
        refused(ProductCategory.ANNUITY, plan(List.of(lifeOnly())), AGES_60_TO_62, SCALE, AccumulationPlan.none(),
            DepositPlan.none(), BonusPlan.none(), FREE_LOOK_ONLY, "An ANNUITY version cannot carry a cash-value table");
    }

    @Test
    void noAccountOrDeposit() {
        refused(ProductCategory.ANNUITY, plan(List.of(lifeOnly())), AGES_60_TO_62, CashValuePlan.none(), ACCOUNT,
            DepositPlan.none(), BonusPlan.none(), FREE_LOOK_ONLY, "An ANNUITY version cannot be valued by an account or as a deposit");
    }

    @Test
    void notWithProfits() {
        refused(ProductCategory.ANNUITY, plan(List.of(lifeOnly())), AGES_60_TO_62, CashValuePlan.none(), AccumulationPlan.none(),
            DepositPlan.none(), new BonusPlan(true, BonusMethod.SIMPLE, false, BonusSurrenderBasis.NONE, List.of()), FREE_LOOK_ONLY,
            "An ANNUITY version cannot be with-profits");
    }

    @Test
    void noPayoutSchedule() {
        refused(ProductCategory.ANNUITY, plan(List.of(lifeOnly())), AGES_60_TO_62, CashValuePlan.none(), AccumulationPlan.none(),
            DepositPlan.none(), BonusPlan.none(), MATURITY,
            "An ANNUITY version carries no payout schedule; its income is set by its forms");
    }

    // ---- Terms ----

    @Test
    void atLeastOneForm() {
        refused(plan(List.of()), "An annuity version needs at least one annuity form");
    }

    @Test
    void atLeastOneFrequency() {
        refused(withFrequencies(List.of()), "An annuity version needs at least one payment frequency");
    }

    @Test
    void timingMustBeStated() {
        refused(new AnnuityPlan(true, null, 12, null, null, "ACT/1", LocalDate.of(2026, 1, 1), List.of(lifeOnly()), FREQUENCIES),
            "An annuity version must state whether income is paid in ARREARS or in ADVANCE");
    }

    @Test
    void basisMustBeStated() {
        refused(new AnnuityPlan(true, AnnuityTiming.ADVANCE, 12, null, null, " ", LocalDate.of(2026, 1, 1), List.of(lifeOnly()), FREQUENCIES),
            "An annuity rate table needs the actuarial basis it was issued under");
        refused(new AnnuityPlan(true, AnnuityTiming.ADVANCE, 12, null, null, "ACT/1", null, List.of(lifeOnly()), FREQUENCIES),
            "An annuity rate table needs the actuarial basis it was issued under");
    }

    @Test
    void proofOfLifeIntervalInRange() {
        refused(new AnnuityPlan(true, AnnuityTiming.ARREARS, 0, null, null, "ACT/1", LocalDate.of(2026, 1, 1), List.of(lifeOnly()), FREQUENCIES),
            "An annuity's proof-of-life interval must be between 1 and 24 months");
    }

    @Test
    void entryAgesMustBeBounded() {
        refused(ProductCategory.ANNUITY, plan(List.of(lifeOnly())), EligibilityBounds.none(), CashValuePlan.none(),
            AccumulationPlan.none(), DepositPlan.none(), BonusPlan.none(), FREE_LOOK_ONLY,
            "An annuity version needs minimum and maximum entry ages, so its grids can be checked for gaps");
    }

    // ---- Forms ----

    @Test
    void guaranteeInRange() {
        refused(plan(List.of(form("G31", 31, false, null, "0", false, AnnuityRateBasis.UNISEX, unisexAges()))),
            "Form G31: a guaranteed period must be between 0 and 30 years");
    }

    @Test
    void survivorOnlyOnJoint() {
        refused(plan(List.of(form("S1", 0, false, "50", "0", false, AnnuityRateBasis.UNISEX, unisexAges()))),
            "Form S1: a survivor percentage is only for a joint-life form");
    }

    @Test
    void jointNeedsASurvivorPercentage() {
        AnnuityForm noSurvivor = form("J0", 0, true, null, "0", false, AnnuityRateBasis.UNISEX, jointHalf().rates());
        refused(plan(List.of(noSurvivor)), "Form J0: a joint-life form needs a survivor percentage between 1 and 100");
        AnnuityForm tooMuch = form("J101", 0, true, "101", "0", false, AnnuityRateBasis.UNISEX, jointHalf().rates());
        refused(plan(List.of(tooMuch)), "Form J101: a joint-life form needs a survivor percentage between 1 and 100");
    }

    @Test
    void escalationInRange() {
        refused(plan(List.of(form("E11", 0, false, null, "11", false, AnnuityRateBasis.UNISEX, unisexAges()))),
            "Form E11: escalation must be between 0% and 10% a year");
    }

    @Test
    void formCodesAreUnique() {
        AnnuityForm twin = form("LIFE-0G", 5, false, null, "0", false, AnnuityRateBasis.UNISEX, unisexAges());
        refused(plan(List.of(lifeOnly(), twin)), "Form code LIFE-0G appears more than once");
    }

    @Test
    void twoFormsMayNotHaveTheSameSettings() {
        AnnuityForm same = form("LIFE-AGAIN", 0, false, null, "0", false, AnnuityRateBasis.UNISEX, unisexAges());
        refused(plan(List.of(lifeOnly(), same)), "Forms LIFE-0G and LIFE-AGAIN have the same settings");
    }

    @Test
    void aJointFormNeedsTheVersionsRangeOfDifferences() {
        refused(new AnnuityPlan(true, AnnuityTiming.ARREARS, 12, null, null, "ACT/1", LocalDate.of(2026, 1, 1),
            List.of(jointHalf()), FREQUENCIES), "A joint-life form needs the version's range of age differences");
    }

    // ---- Coverage: no gaps ----

    @Test
    void aGapInASingleLifeGridIsRefusedNamingTheAge() {
        refused(plan(List.of(form("LIFE-0G", 0, false, null, "0", false, AnnuityRateBasis.UNISEX,
            List.of(unisex(60, "72"), unisex(62, "76"))))), "Form LIFE-0G has no rate for age 61");
    }

    @Test
    void aBySexGridNeedsBothSexesAtEveryAge() {
        List<AnnuityRateRow> womenOnly = List.of(new AnnuityRateRow("FEMALE", 60, null, null, new BigDecimal("70")),
            new AnnuityRateRow("FEMALE", 61, null, null, new BigDecimal("71")),
            new AnnuityRateRow("FEMALE", 62, null, null, new BigDecimal("72")));
        refused(plan(List.of(form("LIFE-BS", 0, false, null, "0", false, AnnuityRateBasis.BY_SEX, womenOnly))),
            "Form LIFE-BS has no rate for a MALE aged 60");
    }

    @Test
    void aGapInAJointBandIsRefusedNamingTheDifference() {
        List<AnnuityRateRow> rows = new ArrayList<>(jointHalf().rates());
        rows.removeIf(r -> r.age() == 61 && r.ageDifferenceFrom() == 0);
        refused(plan(List.of(form("JOINT-50", 0, true, "50", "0", false, AnnuityRateBasis.UNISEX, rows))),
            "Form JOINT-50 has no rate for age 61 with an age difference of 0");
    }

    @Test
    void overlappingJointBandsAreRefused() {
        List<AnnuityRateRow> rows = new ArrayList<>(jointHalf().rates());
        rows.add(joint(60, 1, 2, "61"));
        refused(plan(List.of(form("JOINT-50", 0, true, "50", "0", false, AnnuityRateBasis.UNISEX, rows))),
            "Form JOINT-50 has overlapping age-difference bands at age 60");
    }

    @Test
    void aUnisexRowCarriesNoSexAndABySexRowNamesOne() {
        List<AnnuityRateRow> withSex = List.of(new AnnuityRateRow("FEMALE", 60, null, null, new BigDecimal("72")),
            unisex(61, "74"), unisex(62, "76"));
        refused(plan(List.of(form("U", 0, false, null, "0", false, AnnuityRateBasis.UNISEX, withSex))),
            "Form U: a UNISEX form's rates carry no sex");
        refused(plan(List.of(form("B", 0, false, null, "0", false, AnnuityRateBasis.BY_SEX, unisexAges()))),
            "Form B: a BY_SEX form's rates each name a sex");
    }

    @Test
    void everyRateIsPositive() {
        refused(plan(List.of(form("Z", 0, false, null, "0", false, AnnuityRateBasis.UNISEX,
            List.of(unisex(60, "0"), unisex(61, "74"), unisex(62, "76"))))), "Form Z: every rate must be greater than zero");
    }

    // ---- Frequencies ----

    @Test
    void aFrequencyFactorIsBetweenZeroAndOne() {
        refused(withFrequencies(List.of(new AnnuityFrequencyFactor("MONTHLY", new BigDecimal("1.01")))),
            "A frequency factor must be greater than 0 and at most 1");
        refused(withFrequencies(List.of(new AnnuityFrequencyFactor("MONTHLY", BigDecimal.ZERO))),
            "A frequency factor must be greater than 0 and at most 1");
    }

    @Test
    void theAnnualFactorIsOne() {
        refused(withFrequencies(List.of(new AnnuityFrequencyFactor("ANNUAL", new BigDecimal("0.99")))),
            "The ANNUAL frequency factor is 1");
    }

    @Test
    void aFrequencyAppearsOnce() {
        refused(withFrequencies(List.of(new AnnuityFrequencyFactor("MONTHLY", new BigDecimal("0.98")),
            new AnnuityFrequencyFactor("MONTHLY", new BigDecimal("0.97")))), "Frequency MONTHLY appears more than once");
    }

    @Test
    void anUnknownFrequencyIsRefused() {
        refused(withFrequencies(List.of(new AnnuityFrequencyFactor("WEEKLY", new BigDecimal("0.9")))),
            "WEEKLY is not an annuity payment frequency (MONTHLY, QUARTERLY, SEMI_ANNUAL or ANNUAL)");
    }
}
