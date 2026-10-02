package tz.co.nlolo.lifeplatform.accumulation.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A correction a person proposed; posted as an ADJUSTMENT entry only once a second person approves. */
public record AdjustmentView(UUID adjustmentId, String policyNumber, BigDecimal amount, String reason, String status,
                             String proposedBy, Instant proposedAt, String decidedBy, Instant decidedAt) {}
