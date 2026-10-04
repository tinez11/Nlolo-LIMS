package tz.co.nlolo.lifeplatform.product.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * An ANNUITY version's terms (product step 5): the forms it offers, their grids, and its payment
 * frequencies. {@link #none()} is every other version.
 *
 * @param jointAgeDifferenceMin the range of annuitant-minus-joint-life ages every joint form's bands
 *                              must cover; null when the version has no joint form
 */
public record AnnuityPlan(boolean annuity, AnnuityTiming timing, int proofOfLifeIntervalMonths,
                          Integer jointAgeDifferenceMin, Integer jointAgeDifferenceMax,
                          String basisReference, LocalDate basisDate,
                          List<AnnuityForm> forms, List<AnnuityFrequencyFactor> frequencies) {

    public AnnuityPlan {
        forms = forms != null ? List.copyOf(forms) : List.of();
        frequencies = frequencies != null ? List.copyOf(frequencies) : List.of();
    }

    public static AnnuityPlan none() {
        return new AnnuityPlan(false, null, 0, null, null, null, null, List.of(), List.of());
    }

    public Optional<AnnuityForm> form(String formCode) {
        return forms.stream().filter(f -> f.formCode().equals(formCode)).findFirst();
    }

    public Optional<AnnuityFrequencyFactor> frequency(String frequency) {
        return frequencies.stream().filter(f -> f.frequency().equals(frequency)).findFirst();
    }
}
