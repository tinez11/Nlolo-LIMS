package tz.co.nlolo.lifeplatform.annuity.infrastructure;

import tz.co.nlolo.lifeplatform.product.api.AnnuityPrice;

import java.util.Map;

/** A priced purchase and the cell it came from (product step 5). Money as decimal strings, rates without trailing zeros. */
public record AnnuityQuoteResponse(String formCode, String frequency, int paymentsPerYear, String timing,
                                   Map<String, String> instalment, Map<String, String> annualIncome,
                                   int annuitantAge, Integer jointAge, Integer ageDifference, String rateSex,
                                   String annualRatePerMille, String factor) {

    static AnnuityQuoteResponse from(AnnuityPrice p, String currency) {
        return new AnnuityQuoteResponse(p.formCode(), p.frequency(), p.paymentsPerYear(), p.timing().name(),
            AnnuityController.money(p.instalment(), currency), AnnuityController.money(p.annualIncome(), currency),
            p.annuitantAge(), p.jointAge(), p.ageDifference(), p.rateSex(),
            AnnuityController.plain(p.annualRatePerMille()), AnnuityController.plain(p.factor()));
    }
}
