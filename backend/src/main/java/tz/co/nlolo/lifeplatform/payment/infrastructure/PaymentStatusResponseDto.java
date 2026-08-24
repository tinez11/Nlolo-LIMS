package tz.co.nlolo.lifeplatform.payment.infrastructure;

import tz.co.nlolo.lifeplatform.payment.api.DisbursementStatusView;
import tz.co.nlolo.lifeplatform.payment.api.PaymentStatusView;

import java.util.UUID;

/**
 * The wire shape for both {@code /payments/{paymentRequestId}/status} (always {@code kind:
 * PAYMENT}) and {@code /payments/status-by-key} (either kind, since that lookup resolves
 * against both ledgers -- see {@code PaymentApi.IdempotencyLookupResult}). Deliberately narrower
 * than the domain views it wraps: openapi-payment.yaml's {@code PaymentStatusView} schema
 * declares only id/idempotencyKey/kind/status/amount, not gatewayReference/sourceRef/purpose/
 * batchId, none of which an external caller needs. {@code status} is a plain String because the
 * combined vocabulary (PENDING/CONFIRMED/COMPLETED/FAILED) spans two different domain enums
 * ({@link tz.co.nlolo.lifeplatform.payment.api.PaymentStatus} has no COMPLETED,
 * {@link tz.co.nlolo.lifeplatform.payment.api.DisbursementStatus} has no CONFIRMED) with no
 * single Java enum unifying them.
 */
public record PaymentStatusResponseDto(UUID id, String idempotencyKey, String kind, String status, MoneyDto amount) {

    public static PaymentStatusResponseDto from(PaymentStatusView view) {
        return new PaymentStatusResponseDto(view.paymentRequestId(), view.idempotencyKey(), "PAYMENT",
            view.status().name(), new MoneyDto(view.amount().toPlainString(), view.currency()));
    }

    public static PaymentStatusResponseDto from(DisbursementStatusView view) {
        return new PaymentStatusResponseDto(view.disbursementId(), view.idempotencyKey(), "DISBURSEMENT",
            view.status().name(), new MoneyDto(view.amount().toPlainString(), view.currency()));
    }
}
