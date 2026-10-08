package tz.co.nlolo.lifeplatform.claims.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What a claimant may know about their claim, and the documents staff ask them for (2026-10-08, the customer portal
 * design step 4). Customer-safe by construction: the dates a claim passed its stages, the decision and its coded reason
 * -- never an assessor's findings, a fraud flag or a staff note.
 */
public interface ClaimJourneyApi {

    /**
     * @param reviewStartedAt the first assessment; null until one is recorded
     * @param decidedAt the latest settlement decision; null while undecided (or reopened since)
     * @param declineReason the coded reason a declined claim carries, or null -- free-text decline notes are staff-only
     * @param settledAt when the claim was paid; null unless settled
     */
    record Journey(UUID claimId, Instant receivedAt, Instant reviewStartedAt, Instant decidedAt, Boolean approved,
                   BigDecimal approvedAmount, String currency, String declineReason, Instant settledAt) {}

    record DocumentRequest(UUID requestId, UUID claimId, String document, String reason, String requestedByName,
                           Instant requestedAt, String status, UUID claimEvidenceId, Instant receivedAt) {}

    Journey journey(UUID claimId);

    /** Staff ask the claimant for a document. Refused once the claim is settled. */
    DocumentRequest requestDocument(UUID claimId, String document, String reason, String requestedBy,
                                    String requestedByName);

    List<DocumentRequest> documentRequests(UUID claimId);

    /** The claimant uploaded evidence against an open request: it is received. */
    DocumentRequest fulfil(UUID claimId, UUID requestId, UUID claimEvidenceId);

    DocumentRequest withdraw(UUID claimId, UUID requestId);
}
