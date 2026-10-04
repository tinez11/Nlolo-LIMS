package tz.co.nlolo.lifeplatform.product.infrastructure;

import jakarta.validation.Valid;
import tz.co.nlolo.lifeplatform.product.api.AnnuityForm;
import tz.co.nlolo.lifeplatform.product.api.AnnuityFrequencyFactor;
import tz.co.nlolo.lifeplatform.product.api.AnnuityPlan;
import tz.co.nlolo.lifeplatform.product.api.AnnuityRateBasis;
import tz.co.nlolo.lifeplatform.product.api.AnnuityRateRow;
import tz.co.nlolo.lifeplatform.product.api.AnnuityTiming;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * An ANNUITY version's terms on the wire (product step 5). Nothing is @NotNull: the validator
 * refuses an absence in the words the console mirrors, and a bean-validation 400 would say
 * something else -- BonusRequest's reason.
 */
public record AnnuityRequest(AnnuityTiming timing, Integer proofOfLifeIntervalMonths,
                             Integer jointAgeDifferenceMin, Integer jointAgeDifferenceMax,
                             String basisReference, LocalDate basisDate,
                             @Valid List<Form> forms, @Valid List<Frequency> frequencies) {

    public record Form(String formCode, Integer guaranteeYears, Boolean joint, BigDecimal survivorPercent,
                       BigDecimal escalationPercent, Boolean capitalProtected, AnnuityRateBasis rateBasis,
                       @Valid List<Rate> rates) {}

    public record Rate(String sex, Integer age, Integer ageDifferenceFrom, Integer ageDifferenceTo,
                       BigDecimal annualRatePerMille) {}

    public record Frequency(String frequency, BigDecimal factor) {}

    public AnnuityPlan toPlan() {
        return new AnnuityPlan(true, timing, proofOfLifeIntervalMonths != null ? proofOfLifeIntervalMonths : 0,
            jointAgeDifferenceMin, jointAgeDifferenceMax, basisReference, basisDate,
            forms == null ? List.of() : forms.stream().map(AnnuityRequest::toForm).toList(),
            frequencies == null ? List.of() : frequencies.stream()
                .map(f -> new AnnuityFrequencyFactor(f.frequency(), f.factor())).toList());
    }

    private static int orZero(Integer value) {
        return value != null ? value : 0;
    }

    private static boolean isTrue(Boolean value) {
        return Boolean.TRUE.equals(value);
    }

    /** A missing age becomes -1, which matches no age, so the coverage check names the gap. */
    static AnnuityForm toForm(Form f) {
        return new AnnuityForm(f.formCode(), orZero(f.guaranteeYears()), isTrue(f.joint()), f.survivorPercent(),
            f.escalationPercent(), isTrue(f.capitalProtected()), f.rateBasis(),
            f.rates() == null ? List.of() : f.rates().stream()
                .map(r -> new AnnuityRateRow(r.sex(), r.age() != null ? r.age() : -1, r.ageDifferenceFrom(),
                    r.ageDifferenceTo(), r.annualRatePerMille()))
                .toList());
    }
}
