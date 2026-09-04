package tz.co.nlolo.lifeplatform.billing.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One agent-captured premium receipt: cash an agent says they took in the field, and whether the
 * platform has matched it to a payment yet.
 *
 * <p>This view exists because the module could raise an alert about these and show nobody a
 * single one. {@code POST /agents/{id}/field-receipts} was the only endpoint the entity had --
 * capture, with no read anywhere -- while {@code observability/alert-rules.yml} carries a live
 * medium-severity {@code FieldReceiptReconciliationOverdue} alert whose description is "one or
 * more agent-captured receipts have exceeded the SLA". An alert that names no receipt can only
 * ever escalate to somebody querying the database by hand.
 *
 * <p>Both timestamps are carried, and the gap between them is the point. {@code capturedAtClient}
 * is when the agent recorded it on a phone that may have been offline for hours;
 * {@code capturedAtServer} is when it reached the platform. The SLA runs from the server time,
 * but a large gap is itself worth seeing on a reconciliation screen -- it is the difference
 * between "we were slow" and "the field was offline".
 *
 * <p>{@code clientIdempotencyKey} is deliberately NOT exposed. It is the field app's dedup token,
 * not a fact about the money, and putting it on a screen invites somebody to treat it as a
 * reference to quote back.
 */
public record FieldReceiptView(
    UUID receiptId,
    String policyNumber,
    UUID agentId,
    BigDecimal amount,
    String currency,
    /** When the agent recorded it, which may be well before the platform saw it. */
    Instant capturedAtClient,
    /** When it reached the platform. The reconciliation SLA runs from here. */
    Instant capturedAtServer,
    /** PENDING_RECONCILIATION, RECONCILED, or RECONCILIATION_OVERDUE. */
    String status,
    /** Null until a matching payment is confirmed. */
    Instant reconciledAt) {}
