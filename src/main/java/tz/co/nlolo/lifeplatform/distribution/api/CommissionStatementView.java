package tz.co.nlolo.lifeplatform.distribution.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Read view of {@code distribution.domain.CommissionStatement}. {@code status} carries the full
 * request/confirm lifecycle ({@link StatementStatus}: OPEN/CLOSED/PAYOUT_REQUESTED/PAID/
 * PAYOUT_FAILED), which openapi-distribution.yaml's own two-value CommissionStatementView schema
 * (PENDING/PAID) predates -- Task 9 reconciles the OpenAPI spec against V2's real lifecycle. */
public record CommissionStatementView(UUID statementId, UUID agentId, String period, BigDecimal totalAmount,
                                       String totalCurrency, StatementStatus status,
                                       Instant closedAt, Instant paidAt) {}
