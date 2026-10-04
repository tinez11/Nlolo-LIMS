package tz.co.nlolo.lifeplatform.underwriting.api;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A deferred annuity applicant's choice (product step 5, D2): only the retirement age -- the form is
 * chosen near vesting. The case's sum assured is the contribution per payment (plan R3).
 *
 * @param targetDate            the date of birth plus the retirement age: the confirmed date of birth
 *                              once accepted, the party's recorded one before that
 * @param ageEvidenceConfirmedBy who confirmed proof of age when accepting; null until then
 * @param confirmedDateOfBirth  the date of birth whose proof was seen at acceptance (spec Q8)
 * @param confirmedSex          the sex recorded at acceptance; null when the party had none
 */
public record DeferredAnnuityChoice(int retirementAge, LocalDate targetDate,
                                    String ageEvidenceConfirmedBy, Instant ageEvidenceConfirmedAt,
                                    LocalDate confirmedDateOfBirth, String confirmedSex) {}
