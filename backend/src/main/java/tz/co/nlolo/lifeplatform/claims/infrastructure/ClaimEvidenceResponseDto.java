package tz.co.nlolo.lifeplatform.claims.infrastructure;

import tz.co.nlolo.lifeplatform.claims.api.ClaimEvidenceView;

import java.time.Instant;
import java.util.UUID;

public record ClaimEvidenceResponseDto(UUID claimEvidenceId, UUID claimId, String documentRef, String description,
                                        String uploadedBy, Instant uploadedAt) {

    public static ClaimEvidenceResponseDto from(ClaimEvidenceView view) {
        return new ClaimEvidenceResponseDto(view.claimEvidenceId(), view.claimId(), view.documentRef(),
            view.description(), view.uploadedBy(), view.uploadedAt());
    }
}
