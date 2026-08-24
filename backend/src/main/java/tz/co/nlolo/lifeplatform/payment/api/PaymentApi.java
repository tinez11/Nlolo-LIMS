package tz.co.nlolo.lifeplatform.payment.api;

import java.util.UUID;

/**
 * Read-only by design. docs/02-module-architecture.md:65: "no module is in `payment`'s
 * allowed-dependency list, and `payment`'s public API is read-only (getStatus(...)) to
 * everything except its own internal event listeners." Every write enters through an event.
 *
 * <p>docs/02:135-137 sketches getStatus(PaymentRequestId)/getStatus(DisbursementId) overloads,
 * which would need wrapper id types to compile. This interface deliberately deviates to raw
 * UUIDs with distinct method names, per the platform-wide "value types stay raw" convention
 * PolicyApi/PolicyLoanApi/BillingApi all follow. Flagged, not silent.
 */
public interface PaymentApi {

    PaymentStatusView getPaymentStatus(UUID paymentRequestId);

    DisbursementStatusView getDisbursementStatus(UUID disbursementId);

    /**
     * Deliverable 3 Rev 2 (Pay2): for a client that lost track of the generated id — "a real
     * scenario after a USSD session drop" — and holds only the key it supplied. Resolves against
     * BOTH ledgers, since a caller holding a key does not necessarily know which kind it was.
     */
    IdempotencyLookupResult getStatusByIdempotencyKey(String idempotencyKey);

    /** Which ledger a key resolved to, so a caller can read the right view. Exactly one of the
     * two views is non-null. */
    record IdempotencyLookupResult(PaymentStatusView payment, DisbursementStatusView disbursement) {}

    PayoutBatchView getPayoutBatch(UUID batchId);
}
