package tz.co.nlolo.lifeplatform.accumulation.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Money that arrived from another scheme, with what it came from. */
public record TransferInView(UUID transferId, String policyNumber, BigDecimal amount, String currency,
                             String sourceScheme, String documentRef, String recordedBy, Instant recordedAt) {}
