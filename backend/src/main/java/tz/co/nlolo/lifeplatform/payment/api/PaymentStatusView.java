package tz.co.nlolo.lifeplatform.payment.api;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentStatusView(UUID paymentRequestId, String idempotencyKey, PaymentStatus status,
                                 BigDecimal amount, String currency, String gatewayReference, String sourceRef) {}
