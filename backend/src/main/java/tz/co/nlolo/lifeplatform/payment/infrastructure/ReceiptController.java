package tz.co.nlolo.lifeplatform.payment.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.payment.api.ReceiptMethod;
import tz.co.nlolo.lifeplatform.payment.api.ReceiptView;
import tz.co.nlolo.lifeplatform.payment.application.Receipts;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Receipts for premium the gateway did not pull (2026-10-27): cash, a bank deposit, a cheque, a Lipa-number payment, a
 * TANQR scan. Finance records one (collections.manage); a second finance person approves or refuses it
 * (collections.approve), and only an approved receipt pays anything.
 */
@RestController
public class ReceiptController {

    public record RecordReceiptRequest(
        @NotNull UUID invoiceId,
        @NotNull ReceiptMethod method,
        @NotBlank @Size(max = 100) String reference,
        @NotNull MoneyDto amount,
        @NotNull LocalDate receivedOn,
        @Size(max = 255) String payerRef,
        @Size(max = 500) String note,
        UUID fieldReceiptId) {}

    public record RejectReceiptRequest(@NotBlank @Size(max = 500) String reason) {}

    public record ReceiptResponse(UUID receiptId, String method, String reference, String payerRef, MoneyDto amount,
                                  UUID invoiceId, String policyNumber, LocalDate receivedOn, String note,
                                  UUID fieldReceiptId, String status, String recordedBy, Instant recordedAt,
                                  String decidedBy, Instant decidedAt, String decisionReason) {
        static ReceiptResponse from(ReceiptView v) {
            return new ReceiptResponse(v.receiptId(), v.method(), v.reference(), v.payerRef(),
                new MoneyDto(v.amount().toPlainString(), v.currency()), v.invoiceId(), v.policyNumber(), v.receivedOn(),
                v.note(), v.fieldReceiptId(), v.status(), v.recordedBy(), v.recordedAt(), v.decidedBy(), v.decidedAt(),
                v.decisionReason());
        }
    }

    private final Receipts receipts;

    public ReceiptController(Receipts receipts) {
        this.receipts = receipts;
    }

    @PostMapping("/receipts")
    @PreAuthorize("@access.can('collections.manage')")
    public ResponseEntity<ReceiptResponse> record(@Valid @RequestBody RecordReceiptRequest body,
                                                  @AuthenticationPrincipal Jwt jwt) {
        ReceiptView recorded = receipts.record(new Receipts.NewReceipt(body.invoiceId(), body.method(), body.reference(),
            new BigDecimal(body.amount().amount()), body.receivedOn(), body.payerRef(), body.note(),
            body.fieldReceiptId()), jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(ReceiptResponse.from(recorded));
    }

    /** The receipts waiting to be confirmed on one invoice -- the invoice's own view of what is in flight. */
    @GetMapping("/receipts")
    @PreAuthorize("@access.canAny('collections.view', 'collections.manage')")
    public ResponseEntity<java.util.List<ReceiptResponse>> waiting(@RequestParam UUID invoiceId) {
        return ResponseEntity.ok(receipts.waitingOn(invoiceId).stream().map(ReceiptResponse::from).toList());
    }

    @GetMapping("/receipts/{receiptId}")
    @PreAuthorize("@access.canAny('collections.view', 'collections.manage')")
    public ResponseEntity<ReceiptResponse> get(@PathVariable UUID receiptId) {
        return ResponseEntity.ok(ReceiptResponse.from(receipts.get(receiptId)));
    }

    @PostMapping("/receipts/{receiptId}/approval")
    @PreAuthorize("@access.can('collections.approve')")
    public ResponseEntity<ReceiptResponse> approve(@PathVariable UUID receiptId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(ReceiptResponse.from(receipts.approve(receiptId, jwt.getSubject())));
    }

    @PostMapping("/receipts/{receiptId}/rejection")
    @PreAuthorize("@access.can('collections.approve')")
    public ResponseEntity<ReceiptResponse> reject(@PathVariable UUID receiptId, @Valid @RequestBody RejectReceiptRequest body,
                                                  @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(ReceiptResponse.from(receipts.reject(receiptId, jwt.getSubject(), body.reason())));
    }
}
