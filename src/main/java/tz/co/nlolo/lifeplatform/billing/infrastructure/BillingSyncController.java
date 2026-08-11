package tz.co.nlolo.lifeplatform.billing.infrastructure;

import tz.co.nlolo.lifeplatform.billing.api.BillingApi;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
public class BillingSyncController {

    private final BillingApi billingApi;

    public BillingSyncController(BillingApi billingApi) {
        this.billingApi = billingApi;
    }

    @PostMapping("/agents/{agentId}/field-receipts")
    @PreAuthorize("hasRole('REALM_AGENTS')")
    public ResponseEntity<FieldReceiptResponseDto> captureFieldReceipt(@PathVariable UUID agentId,
                                                                        @Valid @RequestBody FieldReceiptRequestDto request) {
        BillingApi.FieldReceiptResult result = billingApi.captureFieldReceipt(agentId, request.policyNumber(),
            new BigDecimal(request.amount().amount()), request.amount().currencyCode(),
            request.clientIdempotencyKey(), request.capturedAt());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new FieldReceiptResponseDto(result.receiptId(), result.status()));
    }
}
