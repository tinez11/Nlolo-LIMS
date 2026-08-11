package tz.co.nlolo.lifeplatform.payment.infrastructure;

import jakarta.validation.constraints.NotBlank;

/**
 * The aggregator's own vocabulary, deliberately untranslated at this layer (openapi-payment.yaml
 * models the request body as a freeform object precisely because this shape is
 * aggregator-specific, not part of this platform's own contract). {@code reference} is the
 * merchant-supplied value this platform originally sent as {@code
 * PaymentGatewayPort.GatewayDisbursementRequest/GatewayCollectionRequest.reference()} -- carried
 * through for logging/traceability only; correlation back to a stored row uses {@code
 * gatewayReference} (the aggregator's own id), matching db-migrations/payment/V2's {@code
 * idx_disbursement_gateway_reference}/{@code idx_payment_transaction_gateway_reference}, added
 * specifically to support this lookup path.
 */
public record MobileMoneyCallbackRequestDto(
    @NotBlank String status,
    String reference,
    @NotBlank String gatewayReference,
    String reason) {}
