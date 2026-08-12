package tz.co.nlolo.lifeplatform.payment.infrastructure;

import jakarta.validation.constraints.NotBlank;

/**
 * The aggregator's own vocabulary, deliberately untranslated at this layer (openapi-payment.yaml
 * models the request body as a freeform object precisely because this shape is
 * aggregator-specific, not part of this platform's own contract).
 *
 * <p>{@code reference} is the merchant-supplied value this platform originally sent as {@code
 * PaymentGatewayPort.GatewayDisbursementRequest/GatewayCollectionRequest.reference()}, i.e.
 * {@code disbursementId.toString()} / {@code paymentTransactionId.toString()}.
 *
 * <p><b>Review fix (C1): it is a correlation INPUT, not merely a log field.</b> This javadoc
 * previously said "carried through for logging/traceability only; correlation back to a stored row
 * uses gatewayReference" -- and that was the bug. The PRIMARY correlation route is still
 * {@code gatewayReference} (the aggregator's own id, indexed by db-migrations/payment/V2's
 * {@code idx_disbursement_gateway_reference}/{@code idx_payment_transaction_gateway_reference}), but
 * that column is NULL on exactly the rows most likely to need a callback to recover them: a
 * transport failure or an ACCEPTED-without-reference response never produced an aggregator id to
 * store. {@code reference} is the fallback route for those (db-migrations/payment/V4 section 2,
 * {@code PaymentApiImpl.applyGatewayCallback}).
 *
 * <p>It stays UNVALIDATED beyond nullability, deliberately: it is aggregator-controlled text and
 * the resolution path treats "absent or not a UUID" as simply "no match", never as an error.
 */
public record MobileMoneyCallbackRequestDto(
    @NotBlank String status,
    String reference,
    @NotBlank String gatewayReference,
    String reason) {}
