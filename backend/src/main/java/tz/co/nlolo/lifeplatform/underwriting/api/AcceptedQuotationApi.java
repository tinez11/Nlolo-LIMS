package tz.co.nlolo.lifeplatform.underwriting.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * The quotation a customer agreed to, from confirmation to proposal (2026-10-20, the agents' prospect-to-proposal
 * flow). Quoting writes nothing; {@link #accept} is the first write, made only when the agent confirms the customer
 * agrees.
 *
 * <p><b>Owned by one agent.</b> Every read and act names the caller's identity-provider subject, and a quotation that
 * is not theirs is {@link QuotationNotFoundException} -- the same answer as one that does not exist. A null owner is a
 * staff caller, who may read any.
 */
public interface AcceptedQuotationApi {

    /**
     * Save what the customer agreed to: the inputs as quoted and the server's figures for them.
     *
     * @param applicantPartyId the registered client quoted, or null for a prospect not registered yet
     */
    AcceptedQuotationView accept(String ownerSubject, UUID applicantPartyId, UUID productId, UUID productVersionId,
                                 String inputs, String quoted, LocalDate quotedOn, LocalDate validUntil);

    /** @throws QuotationNotFoundException absent, or not this owner's */
    AcceptedQuotationView get(UUID quotationId, String ownerSubject);

    /**
     * The customer did not go ahead: the quotation is deleted. Refused once a proposal was opened from it, which is
     * then the record of what was agreed.
     */
    void discard(UUID quotationId, String ownerSubject);

    /** A proposal was opened from it. Refused for a quotation already submitted. */
    AcceptedQuotationView markSubmitted(UUID quotationId, String ownerSubject, UUID caseId, UUID applicantPartyId);

    /** The quotation a proposal was opened from; empty for a case opened any other way. */
    Optional<AcceptedQuotationView> forCase(UUID caseId);

    /**
     * Delete every confirmed quotation never submitted, confirmed before {@code before}, in every tenant (user,
     * 2026-10-20: only those that reached the underwriter are kept). Returns how many went.
     */
    int discardUnsubmittedBefore(Instant before);
}
