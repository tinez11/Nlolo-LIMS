package tz.co.nlolo.lifeplatform.product;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.product.api.*;
import tz.co.nlolo.lifeplatform.product.domain.AnnuityPricer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The one annuity pricer (product step 5, plan R1): a cell looked up, a figure computed, rounded once. */
class AnnuityPricerTest {

    private static final LocalDate PRICED_ON = LocalDate.of(2026, 10, 3);
    private static final BigDecimal PRICE = new BigDecimal("50000000.00");

    private static AnnuityForm lifeOnly() {
        return new AnnuityForm("LIFE-0G", 0, false, null, BigDecimal.ZERO, false, AnnuityRateBasis.UNISEX, List.of(
            new AnnuityRateRow(null, 60, null, null, new BigDecimal("72")),
            new AnnuityRateRow(null, 61, null, null, new BigDecimal("74")),
            new AnnuityRateRow(null, 62, null, null, new BigDecimal("76"))));
    }

    private static AnnuityForm bySex() {
        List<AnnuityRateRow> rows = new ArrayList<>();
        for (int age = 60; age <= 62; age++) {
            rows.add(new AnnuityRateRow("FEMALE", age, null, null, new BigDecimal("70")));
            rows.add(new AnnuityRateRow("MALE", age, null, null, new BigDecimal("75")));
        }
        return new AnnuityForm("LIFE-BS", 0, false, null, BigDecimal.ZERO, false, AnnuityRateBasis.BY_SEX, rows);
    }

    private static AnnuityForm jointHalf() {
        List<AnnuityRateRow> rows = new ArrayList<>();
        for (int age = 60; age <= 62; age++) {
            rows.add(new AnnuityRateRow(null, age, -2, 4, new BigDecimal("62")));
            rows.add(new AnnuityRateRow(null, age, 5, 9, new BigDecimal("65")));
        }
        return new AnnuityForm("JOINT-50", 0, true, new BigDecimal("50"), BigDecimal.ZERO, false, AnnuityRateBasis.UNISEX, rows);
    }

    private static AnnuityPlan plan(AnnuityForm... forms) {
        return new AnnuityPlan(true, AnnuityTiming.ARREARS, 12, -2, 9, "ACT/ANN/2026", LocalDate.of(2026, 1, 1), List.of(forms),
            List.of(new AnnuityFrequencyFactor("MONTHLY", new BigDecimal("0.9800")),
                    new AnnuityFrequencyFactor("QUARTERLY", new BigDecimal("0.9900")),
                    new AnnuityFrequencyFactor("ANNUAL", BigDecimal.ONE)));
    }

    private static AnnuityPricingInput single(String form, String frequency, LocalDate dob, String sex) {
        return new AnnuityPricingInput(form, frequency, PRICE, dob, sex, null, null, PRICED_ON);
    }

    @Test
    void lifeOnlyMonthly() {
        // 50,000,000 at age 60 and 72 per mille a year = 3,600,000 a year; x 0.98 / 12 = 294,000.00 a month.
        AnnuityPrice p = AnnuityPricer.price(plan(lifeOnly()), single("LIFE-0G", "MONTHLY", LocalDate.of(1966, 3, 1), "FEMALE"));
        assertThat(p.annuitantAge()).isEqualTo(60);
        assertThat(p.annualRatePerMille()).isEqualByComparingTo("72");
        assertThat(p.factor()).isEqualByComparingTo("0.98");
        assertThat(p.annualIncome()).isEqualByComparingTo("3600000.00");
        assertThat(p.instalment()).isEqualByComparingTo("294000.00");
        assertThat(p.paymentsPerYear()).isEqualTo(12);
        assertThat(p.timing()).isEqualTo(AnnuityTiming.ARREARS);
        assertThat(p.rateSex()).isNull();
    }

    @Test
    void theInstalmentIsRoundedOnceFromFullPrecision() {
        // 1,000,000.01 at 74 per mille, quarterly 0.99: 1,000,000.01 x 74 x 0.99 / 4,000 = 18,315.000183...
        AnnuityPrice p = AnnuityPricer.price(plan(lifeOnly()), new AnnuityPricingInput("LIFE-0G", "QUARTERLY",
            new BigDecimal("1000000.01"), LocalDate.of(1965, 1, 1), null, null, null, PRICED_ON));
        assertThat(p.annuitantAge()).isEqualTo(61);
        assertThat(p.instalment()).isEqualByComparingTo("18315.00");
        assertThat(p.annualIncome()).isEqualByComparingTo("74000.00");
    }

    @Test
    void ageIsTheLastBirthdayOnThePricingDate() {
        // Born 1966-10-04: still 59 on 2026-10-03, below the grid.
        assertThatThrownBy(() -> AnnuityPricer.price(plan(lifeOnly()), single("LIFE-0G", "MONTHLY", LocalDate.of(1966, 10, 4), null)))
            .isInstanceOf(AnnuityPricingRefusedException.class).hasMessage("No rate for age 59 on form LIFE-0G");
        // Born 1966-10-03: 60 that day.
        assertThat(AnnuityPricer.price(plan(lifeOnly()), single("LIFE-0G", "MONTHLY", LocalDate.of(1966, 10, 3), null))
            .annuitantAge()).isEqualTo(60);
    }

    @Test
    void aBySexFormUsesTheSexAndRefusesAnUnrecordedOne() {
        AnnuityPrice male = AnnuityPricer.price(plan(bySex()), single("LIFE-BS", "ANNUAL", LocalDate.of(1965, 1, 1), "MALE"));
        assertThat(male.rateSex()).isEqualTo("MALE");
        assertThat(male.instalment()).isEqualByComparingTo("3750000.00");
        assertThatThrownBy(() -> AnnuityPricer.price(plan(bySex()), single("LIFE-BS", "ANNUAL", LocalDate.of(1965, 1, 1), null)))
            .isInstanceOf(AnnuityPricingRefusedException.class)
            .hasMessage("Form LIFE-BS is priced by sex and the annuitant's sex is not recorded");
    }

    @Test
    void jointUsesTheBandOfTheAgeDifference() {
        // annuitant 62, joint life 57: difference 5, band 5..9 at age 62 = 65 per mille.
        AnnuityPrice p = AnnuityPricer.price(plan(jointHalf()), new AnnuityPricingInput("JOINT-50", "ANNUAL", PRICE,
            LocalDate.of(1964, 1, 1), null, LocalDate.of(1969, 1, 1), null, PRICED_ON));
        assertThat(p.annuitantAge()).isEqualTo(62);
        assertThat(p.jointAge()).isEqualTo(57);
        assertThat(p.ageDifference()).isEqualTo(5);
        assertThat(p.annualIncome()).isEqualByComparingTo("3250000.00");
    }

    @Test
    void aJointFormNeedsTheJointLife() {
        assertThatThrownBy(() -> AnnuityPricer.price(plan(jointHalf()), single("JOINT-50", "ANNUAL", LocalDate.of(1964, 1, 1), null)))
            .isInstanceOf(AnnuityPricingRefusedException.class)
            .hasMessage("Form JOINT-50 is joint-life and no joint life was given");
    }

    @Test
    void anAgeDifferenceOutsideTheBandsIsRefused() {
        // joint life 52 against 62: difference 10, beyond the version's range.
        assertThatThrownBy(() -> AnnuityPricer.price(plan(jointHalf()), new AnnuityPricingInput("JOINT-50", "ANNUAL", PRICE,
                LocalDate.of(1964, 1, 1), null, LocalDate.of(1974, 1, 1), null, PRICED_ON)))
            .isInstanceOf(AnnuityPricingRefusedException.class)
            .hasMessage("No rate for age 62 with an age difference of 10 on form JOINT-50");
    }

    @Test
    void anUnofferedFormOrFrequencyIsRefused() {
        assertThatThrownBy(() -> AnnuityPricer.price(plan(lifeOnly()), single("NOPE", "MONTHLY", LocalDate.of(1965, 1, 1), null)))
            .isInstanceOf(AnnuityPricingRefusedException.class).hasMessage("This version does not offer form NOPE");
        assertThatThrownBy(() -> AnnuityPricer.price(plan(lifeOnly()), single("LIFE-0G", "SEMI_ANNUAL", LocalDate.of(1965, 1, 1), null)))
            .isInstanceOf(AnnuityPricingRefusedException.class).hasMessage("This version does not offer SEMI_ANNUAL payments");
    }

    @Test
    void aPurchasePriceMustBePositiveAndADateOfBirthKnown() {
        assertThatThrownBy(() -> AnnuityPricer.price(plan(lifeOnly()), new AnnuityPricingInput("LIFE-0G", "MONTHLY",
                BigDecimal.ZERO, LocalDate.of(1965, 1, 1), null, null, null, PRICED_ON)))
            .isInstanceOf(AnnuityPricingRefusedException.class).hasMessage("A purchase price must be greater than zero");
        assertThatThrownBy(() -> AnnuityPricer.price(plan(lifeOnly()), single("LIFE-0G", "MONTHLY", null, null)))
            .isInstanceOf(AnnuityPricingRefusedException.class)
            .hasMessage("The annuitant's date of birth is not recorded, so there is no age to price");
    }

    @Test
    void aVersionThatIsNotAnAnnuityIsRefused() {
        assertThatThrownBy(() -> AnnuityPricer.price(AnnuityPlan.none(), single("LIFE-0G", "MONTHLY", LocalDate.of(1965, 1, 1), null)))
            .isInstanceOf(AnnuityPricingRefusedException.class).hasMessage("This product version is not an annuity");
    }
}
