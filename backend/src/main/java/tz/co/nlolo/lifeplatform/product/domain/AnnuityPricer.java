package tz.co.nlolo.lifeplatform.product.domain;

import tz.co.nlolo.lifeplatform.product.api.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Period;
import java.util.List;
import java.util.Objects;

/**
 * The one annuity pricer (product step 5, plan R1). Underwriting asks it at acceptance and the
 * annuity module asks it at the lock, so a quote and the income it becomes are one calculation.
 *
 * <p>annual income = price x rate / 1,000; instalment = price x rate x factor / (1,000 x payments
 * per year), each computed at full precision and rounded HALF_EVEN to cents once -- the instalment is
 * never derived from the rounded annual figure.
 */
public final class AnnuityPricer {

    private static final BigDecimal THOUSAND = new BigDecimal("1000");

    private AnnuityPricer() {}

    public static AnnuityPrice price(AnnuityPlan plan, AnnuityPricingInput in) {
        if (plan == null || !plan.annuity()) {
            throw refused("This product version is not an annuity");
        }
        AnnuityForm form = plan.form(in.formCode())
            .orElseThrow(() -> refused("This version does not offer form " + in.formCode()));
        AnnuityFrequencyFactor frequency = plan.frequency(in.frequency())
            .orElseThrow(() -> refused("This version does not offer " + in.frequency() + " payments"));
        if (in.purchasePrice() == null || in.purchasePrice().signum() <= 0) {
            throw refused("A purchase price must be greater than zero");
        }
        if (in.annuitantDateOfBirth() == null) {
            throw refused("The annuitant's date of birth is not recorded, so there is no age to price");
        }
        boolean bySex = form.rateBasis() == AnnuityRateBasis.BY_SEX;
        if (bySex && in.annuitantSex() == null) {
            throw refused("Form " + form.formCode() + " is priced by sex and the annuitant's sex is not recorded");
        }
        int age = Period.between(in.annuitantDateOfBirth(), in.pricingDate()).getYears();
        String rateSex = bySex ? in.annuitantSex() : null;

        Integer jointAge = null;
        Integer difference = null;
        List<AnnuityRateRow> candidates = form.rates().stream()
            .filter(r -> r.age() == age && Objects.equals(r.sex(), rateSex)).toList();
        AnnuityRateRow cell;
        if (form.joint()) {
            if (in.jointDateOfBirth() == null) {
                throw refused("Form " + form.formCode() + " is joint-life and no joint life was given");
            }
            if (bySex && in.jointSex() == null) {
                throw refused("Form " + form.formCode() + " is priced by sex and the joint life's sex is not recorded");
            }
            jointAge = Period.between(in.jointDateOfBirth(), in.pricingDate()).getYears();
            final int diff = age - jointAge;
            difference = diff;
            cell = candidates.stream()
                .filter(r -> r.ageDifferenceFrom() != null && r.ageDifferenceFrom() <= diff && diff <= r.ageDifferenceTo())
                .findFirst()
                .orElseThrow(() -> refused("No rate for age " + age + " with an age difference of " + diff
                    + " on form " + form.formCode()));
        } else {
            cell = candidates.stream().findFirst()
                .orElseThrow(() -> refused("No rate for age " + age + " on form " + form.formCode()));
        }

        int paymentsPerYear = AnnuityFrequencies.paymentsPerYear(frequency.frequency());
        BigDecimal priceTimesRate = in.purchasePrice().multiply(cell.annualRatePerMille());
        BigDecimal annual = priceTimesRate.divide(THOUSAND, 2, RoundingMode.HALF_EVEN);
        BigDecimal instalment = priceTimesRate.multiply(frequency.factor())
            .divide(THOUSAND.multiply(BigDecimal.valueOf(paymentsPerYear)), 2, RoundingMode.HALF_EVEN);
        return new AnnuityPrice(form.formCode(), frequency.frequency(), age, jointAge, difference, rateSex,
            cell.annualRatePerMille(), frequency.factor(), annual, instalment, paymentsPerYear, plan.timing());
    }

    private static AnnuityPricingRefusedException refused(String message) {
        return new AnnuityPricingRefusedException(message);
    }
}
