package tz.co.nlolo.lifeplatform.claims.infrastructure;

import tz.co.nlolo.lifeplatform.claims.api.ClaimAssessmentView;

import java.time.Instant;
import java.util.UUID;

public record ClaimAssessmentResponseDto(UUID claimAssessmentId, UUID claimId, String assessor, String findings,
                                          MoneyDto recommendedAmount, boolean fraudIndicator, Instant createdAt) {

    public static ClaimAssessmentResponseDto from(ClaimAssessmentView view) {
        return new ClaimAssessmentResponseDto(view.claimAssessmentId(), view.claimId(), view.assessor(),
            view.findings(), new MoneyDto(view.recommendedAmount().toPlainString(), view.recommendedCurrency()),
            view.fraudIndicator(), view.createdAt());
    }
}
