package tz.co.nlolo.lifeplatform.unitlinked.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Money a price correction moved on a payout already made (spec §3): owed to or by the customer, until decided. */
public record AdjustmentView(UUID adjustmentId, String policyNumber, UUID correctedPriceId, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
                             String direction, String status, String proposedBy, String decidedBy, Instant decidedAt,
                             String reason) {}
