package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.api.AnnuityForm;
import tz.co.nlolo.lifeplatform.product.api.AnnuityPlan;
import tz.co.nlolo.lifeplatform.product.api.VestingTerms;

import java.math.BigDecimal;
import java.util.List;

/**
 * What a case form needs to offer an annuity (product step 5, plan R6): the forms and frequencies,
 * and NOT the rate rows -- only the pricer reads those, and a quote is how anyone sees a figure.
 * Decimals are strings without trailing zeros, as DeclarationResponse's rates are.
 */
public record AnnuityTermsResponse(String timing, int proofOfLifeIntervalMonths, List<Form> forms, List<Frequency> frequencies,
                                   Vesting vesting) {

    public record Form(String formCode, int guaranteeYears, boolean joint, String survivorPercent,
                       String escalationPercent, boolean capitalProtected, String rateBasis) {}

    public record Frequency(String frequency, String factor) {}

    /** A deferred annuity's vesting terms (D2); null on an immediate annuity. */
    public record Vesting(int minVestingAge, int maxVestingAge, String defaultFormCode, String defaultFrequency,
                          String maxCommutationPercent, boolean surrenderBeforeVesting) {}

    static AnnuityTermsResponse from(AnnuityPlan plan) {
        VestingTerms v = plan.vesting();
        return new AnnuityTermsResponse(plan.timing().name(), plan.proofOfLifeIntervalMonths(),
            plan.forms().stream().map(AnnuityTermsResponse::form).toList(),
            plan.frequencies().stream().map(f -> new Frequency(f.frequency(), plain(f.factor()))).toList(),
            v == null ? null : new Vesting(v.minVestingAge(), v.maxVestingAge(), v.defaultFormCode(), v.defaultFrequency(),
                plain(v.maxCommutationPercent()), Boolean.TRUE.equals(v.surrenderBeforeVesting())));
    }

    private static Form form(AnnuityForm f) {
        return new Form(f.formCode(), f.guaranteeYears(), f.joint(), plain(f.survivorPercent()), plain(f.escalationPercent()),
            f.capitalProtected(), f.rateBasis().name());
    }

    private static String plain(BigDecimal value) {
        return value != null ? value.stripTrailingZeros().toPlainString() : null;
    }
}
