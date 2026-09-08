package tz.co.nlolo.lifeplatform.underwriting.api;

import java.time.LocalDate;
import java.util.List;
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
 *
 * <p>{@code requestedTermMonths}, {@code premiumPayingTermMonths}, {@code premiumFrequency} and
 * {@code beneficiaries} are what the applicant asks for about the CONTRACT, as distinct from
 * the risk. They lived only on {@code POST /policies/manual-issue} until now, which made manual
 * issue the only screen on this platform that could produce a complete policy — one issued on
 * the normal path carried no term, no maturity date, and nobody nominated.
 *
 * <p>All optional, and each for its own reason. A product that does not term genuinely has no
 * term. A premium-paying term shorter than the cover term is a limited-payment policy; equal or
 * absent is the ordinary case. A proposal arriving with the nomination blank is routine, and
 * beneficiaries can still be designated after issuance.
 */
public record ProposalDetails(
    UUID lifeAssuredPartyId,
    String branch,
    String sourceOfBusiness,
    LocalDate proposedCommencementDate,
    Integer requestedTermMonths,
    Integer premiumPayingTermMonths,
    String premiumFrequency,
    List<BeneficiaryNomination> beneficiaries) {

    /** Never null, so callers and the persistence path both stop guarding for it. */
    public ProposalDetails {
        beneficiaries = beneficiaries == null ? List.of() : List.copyOf(beneficiaries);
    }

    /**
     * The pre-capture shape, kept so the existing construction sites need no change.
     *
     * <p>An extra constructor rather than a widened call at every site, for the same reason
     * {@code PolicyApi.IssueRequest} carries two: this is plain Java with no proxy in the way,
     * so delegation is safe here in a way a {@code default} interface method would not be.
     */
    public ProposalDetails(UUID lifeAssuredPartyId, String branch, String sourceOfBusiness,
                            LocalDate proposedCommencementDate) {
        this(lifeAssuredPartyId, branch, sourceOfBusiness, proposedCommencementDate,
            null, null, null, List.of());
    }

    /** No proposal detail recorded: the applicant insures themselves. */
    public static ProposalDetails selfInsured() {
        return new ProposalDetails(null, null, null, null);
    }

    /** The life assured, resolving the self-insured default against the applicant. */
    public UUID resolveLifeAssured(UUID applicantPartyId) {
        return lifeAssuredPartyId != null ? lifeAssuredPartyId : applicantPartyId;
    }
}
