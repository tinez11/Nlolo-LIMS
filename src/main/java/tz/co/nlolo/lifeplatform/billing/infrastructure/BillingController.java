package tz.co.nlolo.lifeplatform.billing.infrastructure;

import tz.co.nlolo.lifeplatform.billing.api.BillingApi;
import tz.co.nlolo.lifeplatform.billing.api.InvoiceStatus;
import tz.co.nlolo.lifeplatform.billing.api.InvoiceView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyView;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
public class BillingController {

    private final BillingApi billingApi;
    private final PolicyApi policyApi;

    public BillingController(BillingApi billingApi, PolicyApi policyApi) {
        this.billingApi = billingApi;
        this.policyApi = policyApi;
    }

    @GetMapping("/policies/{policyNumber}/invoices")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<List<InvoiceResponseDto>> listInvoices(@PathVariable String policyNumber,
                                                                  @RequestParam(required = false) InvoiceStatus status,
                                                                  @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        PolicyView policy = policyApi.getPolicy(policyNumber);
        enforceCustomerOwnPolicyOnly(policy, jwt, authentication);
        List<InvoiceResponseDto> invoices = billingApi.listInvoices(policyNumber, status).stream().map(InvoiceResponseDto::from).toList();
        return ResponseEntity.ok(invoices);
    }

    @GetMapping("/policies/{policyNumber}/invoices/next-due")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS')")
    public ResponseEntity<InvoiceResponseDto> getNextDueInvoice(@PathVariable String policyNumber,
                                                                 @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        PolicyView policy = policyApi.getPolicy(policyNumber);
        enforceCustomerOwnPolicyOnly(policy, jwt, authentication);
        InvoiceView invoice = billingApi.getNextDueInvoice(policyNumber);
        return ResponseEntity.ok(InvoiceResponseDto.from(invoice));
    }

    @PostMapping("/invoices/{invoiceId}/waiver")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<Void> waiveInvoice(@PathVariable UUID invoiceId, @Valid @RequestBody WaiverRequestDto request,
                                              @AuthenticationPrincipal Jwt jwt) {
        billingApi.waiveInvoice(invoiceId, request.reason(), jwt.getSubject());
        return ResponseEntity.ok().build();
    }

    /**
     * Review fix (I1): {@code Idempotency-Key} is REQUIRED on this one endpoint, and is genuinely
     * enforced rather than "accepted, not enforced" as it is on every other endpoint on this
     * platform (PolicyController, PolicyLoanController, UnderwritingController all take it as an
     * optional header and ignore it, a documented per-milestone scope cut). Here it is the value
     * that reaches {@code payment}'s {@code (tenant_id, idempotency_key)} registry and therefore
     * decides whether a request is a safe duplicate or a genuine retry -- see
     * {@code BillingApi.requestPaymentForInvoice}'s javadoc for the single-shot-forever bug that
     * made this necessary.
     *
     * <p>Declared {@code required = false} at the Spring level and then rejected explicitly, rather
     * than {@code required = true}: that gives ONE code path (and one message) for both a missing
     * header and a present-but-blank one, instead of a framework
     * {@code MissingRequestHeaderException} for the first and a separate check for the second. Both
     * land on {@code GlobalExceptionHandler}'s {@code IllegalArgumentException} handler as a 400
     * {@code VALIDATION_ERROR} ProblemDetails, which is what openapi-billing.yaml declares.
     */
    @PostMapping("/invoices/{invoiceId}/payment-request")
    @PreAuthorize("hasRole('REALM_STAFF') or hasRole('REALM_AGENTS')")
    public ResponseEntity<Void> requestPaymentForInvoice(@PathVariable UUID invoiceId,
                                                          @Valid @RequestBody PaymentRequestDto request,
                                                          @RequestHeader(value = "Idempotency-Key", required = false)
                                                          String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required on this endpoint: the same key "
                + "is treated as the same collection attempt (deduplicated), a new key as a new attempt. There is "
                + "deliberately no default -- any default would make a genuine operator retry after a decline "
                + "impossible.");
        }
        billingApi.requestPaymentForInvoice(invoiceId, request.payerRef(), idempotencyKey);
        return ResponseEntity.accepted().build();
    }

    /**
     * Mirrors PolicyController.enforceCustomerOwnPolicyOnly's exact structure: realm-membership
     * check via Authentication.getAuthorities(), then jwt.getClaimAsString("party_id") vs the
     * resource's own owning party id, then a real AccessDeniedException (403) on mismatch --
     * fail-closed on a null claim, not fail-open.
     */
    private void enforceCustomerOwnPolicyOnly(PolicyView policy, Jwt jwt, Authentication authentication) {
        boolean isCustomer = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority).anyMatch("ROLE_REALM_CUSTOMERS"::equals);
        if (!isCustomer) {
            return;
        }
        String ownPartyId = jwt.getClaimAsString("party_id");
        if (ownPartyId == null || !ownPartyId.equals(policy.policyholderPartyId().toString())) {
            throw new AccessDeniedException("Customer does not own this policy");
        }
    }
}
