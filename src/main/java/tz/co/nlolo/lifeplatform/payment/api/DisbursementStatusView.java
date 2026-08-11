package tz.co.nlolo.lifeplatform.payment.api;

import java.math.BigDecimal;
import java.util.UUID;

public record DisbursementStatusView(UUID disbursementId, String idempotencyKey, DisbursementStatus status,
                                      BigDecimal amount, String currency, String purpose,
                                      String gatewayReference, String sourceRef, UUID batchId) {}
