package tz.co.nlolo.lifeplatform.payment.infrastructure;

import tz.co.nlolo.lifeplatform.payment.api.PaymentApi;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The three read endpoints openapi-payment.yaml declares. Staff/agents only, deliberately
 * excluding REALM_CUSTOMERS on all three -- see PaymentApi's own javadoc and
 * openapi-payment.yaml's per-operation notes: payment's tables carry only opaque
 * payer_ref/payee_ref and source_ref, no party_id or policy_number, so there is no column to
 * perform the mandatory object-level ownership check a customer token would require
 * (openapi-common.yaml:149-154, OWASP API #1). Admitting REALM_CUSTOMERS without that check
 * would be a Broken-Object-Level-Authorization hole; refusing the realm is the fail-closed
 * choice, not an oversight.
 */
@RestController
public class PaymentController {

    private final PaymentApi paymentApi;

    public PaymentController(PaymentApi paymentApi) {
        this.paymentApi = paymentApi;
    }

    @GetMapping("/payments/{paymentRequestId}/status")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<PaymentStatusResponseDto> getPaymentStatus(@PathVariable UUID paymentRequestId) {
        return ResponseEntity.ok(PaymentStatusResponseDto.from(paymentApi.getPaymentStatus(paymentRequestId)));
    }

    @GetMapping("/payments/status-by-key")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<PaymentStatusResponseDto> getStatusByIdempotencyKey(@RequestParam String idempotencyKey) {
        PaymentApi.IdempotencyLookupResult result = paymentApi.getStatusByIdempotencyKey(idempotencyKey);
        // Exactly one of the two is non-null (PaymentApi.IdempotencyLookupResult's own contract).
        PaymentStatusResponseDto dto = result.payment() != null
            ? PaymentStatusResponseDto.from(result.payment())
            : PaymentStatusResponseDto.from(result.disbursement());
        return ResponseEntity.ok(dto);
    }

    @GetMapping("/payout-batches/{batchId}")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<PayoutBatchResponseDto> getPayoutBatch(@PathVariable UUID batchId) {
        return ResponseEntity.ok(PayoutBatchResponseDto.from(paymentApi.getPayoutBatch(batchId)));
    }
}
