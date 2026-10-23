package tz.co.nlolo.lifeplatform.annuity.infrastructure;

import tz.co.nlolo.lifeplatform.product.api.AnnuityPrice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A priced purchase and the cell it came from (product step 5). Money as decimal strings, rates without trailing zeros.
 *
 * @param schedule the income received by the end of each year from the first payment (2026-10-23, the quotation's
 *     chart), while the annuitant lives: a year's income is the priced annual income, the same figure every year, to
 *     the annuitant's hundredth birthday or for forty years, whichever comes first. Empty without a purchase price.
 * @param breakEvenYear the first year by whose end the income received is at least the purchase price; null if no
 *     year of the schedule reaches it
 */
public record AnnuityQuoteResponse(String formCode, String frequency, int paymentsPerYear, String timing,
                                   Map<String, String> instalment, Map<String, String> annualIncome,
                                   int annuitantAge, Integer jointAge, Integer ageDifference, String rateSex,
                                   String annualRatePerMille, String factor,
                                   Map<String, String> purchasePrice, List<YearReceived> schedule,
                                   Integer breakEvenYear) {

    /** Income received by the end of {@code year}, when the annuitant is {@code age}. */
    public record YearReceived(int year, int age, Map<String, String> received) {}

    static final int MAX_YEARS = 40;
    static final int TO_AGE = 100;

    static AnnuityQuoteResponse from(AnnuityPrice p, BigDecimal purchasePrice, String currency) {
        List<YearReceived> schedule = new ArrayList<>();
        Integer breakEven = null;
        if (purchasePrice != null && p.annualIncome() != null && p.annualIncome().signum() > 0) {
            int years = Math.max(1, Math.min(MAX_YEARS, TO_AGE - p.annuitantAge()));
            for (int year = 1; year <= years; year++) {
                BigDecimal received = p.annualIncome().multiply(BigDecimal.valueOf(year)).setScale(2, RoundingMode.HALF_UP);
                if (breakEven == null && received.compareTo(purchasePrice) >= 0) {
                    breakEven = year;
                }
                schedule.add(new YearReceived(year, p.annuitantAge() + year, AnnuityController.money(received, currency)));
            }
        }
        return new AnnuityQuoteResponse(p.formCode(), p.frequency(), p.paymentsPerYear(), p.timing().name(),
            AnnuityController.money(p.instalment(), currency), AnnuityController.money(p.annualIncome(), currency),
            p.annuitantAge(), p.jointAge(), p.ageDifference(), p.rateSex(),
            AnnuityController.plain(p.annualRatePerMille()), AnnuityController.plain(p.factor()),
            purchasePrice == null ? null : AnnuityController.money(purchasePrice.setScale(2, RoundingMode.HALF_UP), currency),
            List.copyOf(schedule), breakEven);
    }
}
