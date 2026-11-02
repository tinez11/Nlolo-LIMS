package tz.co.nlolo.lifeplatform.claims.api;

import java.time.Instant;
import java.util.UUID;

/**
 * @param uploadedBy the uploader's identity-provider subject. An identifier, never shown.
 * @param uploadedByName the uploader's display name, captured at upload; null on rows attached
 *     before it was captured.
 * @param kind what the document is where the platform needs to know -- {@code LOAN_STATEMENT} for a lender's loan
 *     statement on a credit-life claim (2026-11-02) -- or null.
 */
public record ClaimEvidenceView(UUID claimEvidenceId, UUID claimId, String documentRef, String description,
                                 String uploadedBy, String uploadedByName, Instant uploadedAt, String kind) {

    public ClaimEvidenceView(UUID claimEvidenceId, UUID claimId, String documentRef, String description,
                             String uploadedBy, String uploadedByName, Instant uploadedAt) {
        this(claimEvidenceId, claimId, documentRef, description, uploadedBy, uploadedByName, uploadedAt, null);
    }
}
