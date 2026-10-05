package tz.co.nlolo.lifeplatform.unitlinked.api;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A top-up (U2): REQUESTED until payment confirms it, then RECEIVED (allocated) or REFUNDED; FAILED if payment did. */
public record TopUpView(UUID topUpId, String policyNumber, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
                        String currency, String payerRef, List<PremiumSplitView.Share> split, String status,
                        String requestedBy, Instant requestedAt, Instant receivedAt) {}
