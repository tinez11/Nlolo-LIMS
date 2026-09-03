package tz.co.nlolo.lifeplatform.underwriting.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * What a proposal records beyond the risk itself.
 *
 * <p>{@code lifeAssuredPartyId} is the consequential one. Until it existed the model had a
 * single {@code applicantPartyId}, so a proposal where the policyholder insures somebody
 * else could not be expressed at all — and most life business is not self-insured. Group
 * business is definitionally one policyholder and many lives assured; credit life is a
 * lender-driven policy on a borrower's life. Neither can be modelled without this.
 *
 * <p><b>Null means self-insured.</b> {@code UnderwritingApiImpl} resolves a null life
 * assured to the applicant, because that is what every case meant when the model could
 * express nothing else. It is a default that states an existing meaning, not one that
 * invents a new fact.
 *
 * <p>{@code sourceOfBusiness} is free text on purpose. The platform does not own that
 * vocabulary — TIRA's return catalogue is still an open item — and minting one here would
 * make it the de facto schema, which is how the endorsement `changes` payload and the M7
 * commission semantics became voids nobody could later ratify.
 */
public record ProposalDetails(
    UUID lifeAssuredPartyId,
    String branch,
    String sourceOfBusiness,
    LocalDate proposedCommencementDate) {

    /** No proposal detail recorded: the applicant insures themselves. */
    public static ProposalDetails selfInsured() {
        return new ProposalDetails(null, null, null, null);
    }

    /** The life assured, resolving the self-insured default against the applicant. */
    public UUID resolveLifeAssured(UUID applicantPartyId) {
        return lifeAssuredPartyId != null ? lifeAssuredPartyId : applicantPartyId;
    }
}
