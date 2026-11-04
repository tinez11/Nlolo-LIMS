package tz.co.nlolo.lifeplatform.billing.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A credit-life lender's premium paid back by bank transfer (billing V13), and where it stands: AWAITING_APPROVAL until a
 * second person releases it (V17, money movement step 4), then REQUESTED, PAID or FAILED; or REFUSED, saying why.
 */
public record LenderRefundView(UUID creditId, String policyNumber, String payee, BigDecimal amount, String currency,
                               String status, String exitReason, Instant raisedAt, String decidedBy,
                               String refusalReason) {}
