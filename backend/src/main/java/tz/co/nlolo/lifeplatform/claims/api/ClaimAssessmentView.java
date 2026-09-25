package tz.co.nlolo.lifeplatform.claims.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * @param assessor the identity-provider subject; what separation of duties compares. Never shown.
 * @param assessorName the display name captured when the assessment was written; null on rows
 *     recorded before it was captured.
 */
public record ClaimAssessmentView(UUID claimAssessmentId, UUID claimId, String assessor, String assessorName,
                                   String findings, BigDecimal recommendedAmount, String recommendedCurrency,
                                   boolean fraudIndicator, Instant createdAt) {}
