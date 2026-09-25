package tz.co.nlolo.lifeplatform.claims.api;

import java.time.Instant;
import java.util.UUID;

/**
 * @param uploadedBy the uploader's identity-provider subject. An identifier, never shown.
 * @param uploadedByName the uploader's display name, captured at upload; null on rows attached
 *     before it was captured.
 */
public record ClaimEvidenceView(UUID claimEvidenceId, UUID claimId, String documentRef, String description,
                                 String uploadedBy, String uploadedByName, Instant uploadedAt) {}
