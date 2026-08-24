package tz.co.nlolo.lifeplatform.claims.api;

import java.time.Instant;
import java.util.UUID;

public record ClaimEvidenceView(UUID claimEvidenceId, UUID claimId, String documentRef, String description,
                                 String uploadedBy, Instant uploadedAt) {}
