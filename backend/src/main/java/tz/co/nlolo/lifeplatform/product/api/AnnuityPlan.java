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
 * @param vesting               a deferred annuity's vesting terms (D2); null on an immediate annuity,
 *                              which is bought with a single premium and pays at once
 */
public record AnnuityPlan(boolean annuity, AnnuityTiming timing, int proofOfLifeIntervalMonths,
                          Integer jointAgeDifferenceMin, Integer jointAgeDifferenceMax,
                          String basisReference, LocalDate basisDate,
                          List<AnnuityForm> forms, List<AnnuityFrequencyFactor> frequencies,
                          VestingTerms vesting) {

    public AnnuityPlan {
        forms = forms != null ? List.copyOf(forms) : List.of();
        frequencies = frequencies != null ? List.copyOf(frequencies) : List.of();
    }

    /** An immediate annuity's terms: every D1 caller's form, unchanged by D2 (plan R1). */
    public AnnuityPlan(boolean annuity, AnnuityTiming timing, int proofOfLifeIntervalMonths,
                       Integer jointAgeDifferenceMin, Integer jointAgeDifferenceMax,
                       String basisReference, LocalDate basisDate,
                       List<AnnuityForm> forms, List<AnnuityFrequencyFactor> frequencies) {
        this(annuity, timing, proofOfLifeIntervalMonths, jointAgeDifferenceMin, jointAgeDifferenceMax,
            basisReference, basisDate, forms, frequencies, null);
    }

    public static AnnuityPlan none() {
        return new AnnuityPlan(false, null, 0, null, null, null, null, List.of(), List.of());
    }

    /** A deferred annuity (D2): it saves in an account and vests into an income. */
    public boolean deferred() {
        return annuity && vesting != null;
    }

    public Optional<AnnuityForm> form(String formCode) {
        return forms.stream().filter(f -> f.formCode().equals(formCode)).findFirst();
    }

    public Optional<AnnuityFrequencyFactor> frequency(String frequency) {
        return frequencies.stream().filter(f -> f.frequency().equals(frequency)).findFirst();
    }
}
