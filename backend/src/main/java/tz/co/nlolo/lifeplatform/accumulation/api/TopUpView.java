package tz.co.nlolo.lifeplatform.accumulation.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Money a customer chose to add, collected through payment and credited only once confirmed. */
public record TopUpView(UUID topUpId, String policyNumber, BigDecimal amount, String currency, String payerRef,
                        String status, String requestedBy, Instant requestedAt) {}
