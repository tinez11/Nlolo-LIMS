package tz.co.nlolo.lifeplatform.underwriting.api;

import java.time.Instant;
import java.util.UUID;

/**
 * What an annuity applicant chose (product step 5): one of the version's forms, a payment frequency,
 * and on a joint-life form the second life. The case's sum assured is the purchase price.
 *
 * @param ageEvidenceConfirmedBy who confirmed proof of age when accepting; null until then
 */
public record AnnuityChoice(String formCode, String frequency, UUID jointLifePartyId,
                            String ageEvidenceConfirmedBy, Instant ageEvidenceConfirmedAt) {

    /** A choice as captured, before any decision. */
    public static AnnuityChoice of(String formCode, String frequency, UUID jointLifePartyId) {
        return new AnnuityChoice(formCode, frequency, jointLifePartyId, null, null);
    }
}
