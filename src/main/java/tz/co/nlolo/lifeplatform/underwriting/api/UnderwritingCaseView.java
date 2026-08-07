package tz.co.nlolo.lifeplatform.underwriting.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record UnderwritingCaseView(UUID caseId, UUID applicantPartyId, UUID productId, UnderwritingCaseStatus status,
                                    ReferralStatus referralStatus, DecisionOutcome decisionOutcome,
                                    BigDecimal decisionLoadingPercent, String decisionDeclineReason, Instant decisionDecidedAt) {}
