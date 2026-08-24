package tz.co.nlolo.lifeplatform.claims.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ClaimAssessmentView(UUID claimAssessmentId, UUID claimId, String assessor, String findings,
                                   BigDecimal recommendedAmount, String recommendedCurrency,
                                   boolean fraudIndicator, Instant createdAt) {}
