package tz.co.nlolo.lifeplatform.billing.infrastructure;

import tz.co.nlolo.lifeplatform.billing.api.BillingApi;
import tz.co.nlolo.lifeplatform.billing.api.InvoiceStatus;
import tz.co.nlolo.lifeplatform.billing.api.InvoiceView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyView;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

    /**
     * The collections queue — the only tenant-wide read this module has.
     *
     * <p>Every other billing endpoint is keyed by a policy number or an invoice id the caller
     * must already know, which meant "which policies are in arrears" could not be asked at all:
     * dunning was visible only as a badge on one invoice row of one policy record. A platform
     * that escalates through five dunning levels and then recommends lapse had no way to show
     * anybody the escalations.
     *
     * <p>Finance-gated, matching the chart of accounts and GL postings rather than the broad
     * customer/agent/staff gate the per-policy invoice reads carry. A per-policy invoice list is
     * something a customer may see about their own contract; a tenant-wide list of who is behind
     * on payments is a collections officer's screen.
     *
     * <p>The sort is fixed and TOTAL, and the order is the work order: worst escalation first,
     * then longest-standing, then {@code arrearsCaseId} as the tie-breaker. The tie-breaker is
     * not decoration — dunning level takes five values and a bulk sweep opens many cases in the
     * same instant, so without it two pages of an unordered query can show one case twice and
     * never show another. On a collections queue that is a customer who is never chased.
     */
    @GetMapping("/arrears")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<ArrearsCaseResponseDto.PageResponse> listArrears(
            @RequestParam(required = false) Integer minDunningLevel,
            @RequestParam(required = false) Boolean resolved,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok(ArrearsCaseResponseDto.PageResponse.from(
            billingApi.searchArrears(minDunningLevel, resolved,
                PageRequest.of(page, Math.min(pageSize, 100),
                    Sort.by(Sort.Order.desc("dunningLevel"),
                            Sort.Order.asc("openedAt"),
                            Sort.Order.asc("arrearsCaseId"))))));
    }

    /**
     * The reconciliation queue: field-captured premium receipts and whether they have matched a
     * payment yet.
     *
     * <p>The read this entity never had. Capture was its only endpoint, so cash an agent
     * recorded in the field could sit unmatched past its SLA, raise the medium-severity
     * {@code FieldReceiptReconciliationOverdue} Prometheus alert, and still be invisible to
     * every human on the platform -- the alert's own description says "one or more
     * agent-captured receipts have exceeded the SLA" and names none of them. The only available
     * follow-up was a hand-written database query.
     *
     * <p>Finance-gated like the arrears queue: this is unreconciled money across the tenant, not
     * one agent's own submissions. An agent-scoped read ("what have I sent in") is a real and
     * separate need, deliberately not built here -- the field app that would call it does not
     * exist yet, and guessing its shape now would be building for an imagined caller.
     *
     * <p>Ordered {@code capturedAtServer} ASC then {@code receiptId} -- oldest unmatched cash
     * first, which is the work order and also the SLA order. The tie-breaker is load-bearing: a
     * bulk sync from one agent lands many receipts in the same instant.
     */
    @GetMapping("/field-receipts")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<FieldReceiptQueueResponseDto.PageResponse> listFieldReceipts(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok(FieldReceiptQueueResponseDto.PageResponse.from(
            billingApi.searchFieldReceipts(status,
                PageRequest.of(page, Math.min(pageSize, 100),
                    Sort.by(Sort.Order.asc("capturedAtServer"), Sort.Order.asc("receiptId"))))));
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

    /**
     * Writing off a premium. Finance-gated, which it was not until now.
     *
     * <p>It was {@code hasRole('REALM_STAFF')} — every staff member, whatever their job, could
     * waive money owed. An underwriter or a claims assessor writing off a premium is not a
     * plausible authorisation on a regulated insurer's ledger, and the endpoint publishes
     * {@code billing.InvoiceWaived} and resolves the arrears case, so the write-off is final and
     * silent from the console's point of view.
     *
     * <p>Matched to the module's other money endpoints (arrears, field receipts, payouts, the
     * chart of accounts) rather than invented: {@code FINANCE_OFFICER or ADMIN}. Requesting a
     * payment is deliberately NOT tightened alongside it — asking a customer to pay takes nothing
     * away from anyone, and agents and customers can both do it already.
     */
    @PostMapping("/invoices/{invoiceId}/waiver")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
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
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_STAFF') or hasRole('REALM_AGENTS')")
    public ResponseEntity<Void> requestPaymentForInvoice(@PathVariable UUID invoiceId,
                                                          @Valid @RequestBody PaymentRequestDto request,
                                                          @RequestHeader(value = "Idempotency-Key", required = false)
                                                          String idempotencyKey,
                                                          @AuthenticationPrincipal Jwt jwt,
                                                          Authentication authentication) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required on this endpoint: the same key "
                + "is treated as the same collection attempt (deduplicated), a new key as a new attempt. There is "
                + "deliberately no default -- any default would make a genuine operator retry after a decline "
                + "impossible.");
        }
        InvoiceView invoice = billingApi.getInvoice(invoiceId);
        enforceCustomerOwnPolicyOnly(policyApi.getPolicy(invoice.policyNumber()), jwt, authentication);
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
