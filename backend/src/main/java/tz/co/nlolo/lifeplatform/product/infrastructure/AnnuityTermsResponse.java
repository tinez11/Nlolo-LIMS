package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.api.AnnuityForm;
import tz.co.nlolo.lifeplatform.product.api.AnnuityPlan;

import java.math.BigDecimal;
import java.util.List;

/**
 * What a case form needs to offer an annuity (product step 5, plan R6): the forms and frequencies,
 * and NOT the rate rows -- only the pricer reads those, and a quote is how anyone sees a figure.
 * Decimals are strings without trailing zeros, as DeclarationResponse's rates are.
 */
public record AnnuityTermsResponse(String timing, int proofOfLifeIntervalMonths, List<Form> forms, List<Frequency> frequencies) {

    public record Form(String formCode, int guaranteeYears, boolean joint, String survivorPercent,
                       String escalationPercent, boolean capitalProtected, String rateBasis) {}

    public record Frequency(String frequency, String factor) {}

    static AnnuityTermsResponse from(AnnuityPlan plan) {
        return new AnnuityTermsResponse(plan.timing().name(), plan.proofOfLifeIntervalMonths(),
            plan.forms().stream().map(AnnuityTermsResponse::form).toList(),
            plan.frequencies().stream().map(f -> new Frequency(f.frequency(), plain(f.factor()))).toList());
    }

    private static Form form(AnnuityForm f) {
        return new Form(f.formCode(), f.guaranteeYears(), f.joint(), plain(f.survivorPercent()), plain(f.escalationPercent()),
            f.capitalProtected(), f.rateBasis().name());
    }

    private static String plain(BigDecimal value) {
        return value != null ? value.stripTrailingZeros().toPlainString() : null;
    }
}
