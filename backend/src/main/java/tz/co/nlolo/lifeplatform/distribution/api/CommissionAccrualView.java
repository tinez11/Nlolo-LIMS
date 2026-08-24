package tz.co.nlolo.lifeplatform.distribution.api;

import java.math.BigDecimal;
import java.util.UUID;

/** Read view of {@code distribution.domain.CommissionAccrual} -- the per-event line items a
 * {@link CommissionStatementView} totals. {@code reversesAccrualId} is non-null exactly for a
 * clawback row (M7 user decision 2). */
public record CommissionAccrualView(UUID accrualId, UUID agentId, UUID statementId, String policyNumber,
                                     TierType tierType, BigDecimal amount, String currency, String period,
                                     String sourceRef, UUID reversesAccrualId) {}
