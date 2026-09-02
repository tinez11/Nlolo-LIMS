package tz.co.nlolo.lifeplatform.policyloan.infrastructure;

import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyView;
import tz.co.nlolo.lifeplatform.policyloan.api.LoanView;
import tz.co.nlolo.lifeplatform.policyloan.api.PolicyLoanApi;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** External REST surface of the policyloan module (api/openapi/openapi-policyloan.yaml).
 * Structural precedent: policy.infrastructure.PolicyController -- same object-level
 * authorization idiom, same Jwt-subject-as-audit-field pattern, same accepted-but-unenforced
 * Idempotency-Key header (Global Constraints). markDisbursed/markDisbursementFailed on
 * PolicyLoanApi are driven by policyloan.application.PaymentEventListener consuming payment's
 * confirmation events (M5), not by HTTP -- they deliberately have no endpoint here.
 *
 * <p>Forced lapse now has two paths and only ONE of them is exposed. The automatic one --
 * evaluateForcedLapse, the shortfall test docs/01-domain-map.md:224 specifies -- is a STAFF
 * endpoint below, together with the review queue the interest-accrual sweep fills.
 * PolicyLoanApi.triggerForcedLapse, which forces a lapse with NO shortfall test, stays
 * endpoint-less on purpose: an unconditional policy termination over HTTP is not a capability any
 * document on this platform asks for, and nothing outside this module needs it. */
@RestController
public class PolicyLoanController {

    private final PolicyLoanApi policyLoanApi;
    private final PolicyApi policyApi;

    public PolicyLoanController(PolicyLoanApi policyLoanApi, PolicyApi policyApi) {
        this.policyLoanApi = policyLoanApi;
        this.policyApi = policyApi;
    }

    @PostMapping("/policies/{policyNumber}/loans")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<LoanResponseDto> originateLoan(@PathVariable String policyNumber, @Valid @RequestBody OriginateLoanRequestDto request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        // Idempotency-Key accepted, not enforced (Global Constraints).
        enforceCustomerOwnPolicyOnly(policyNumber, jwt, authentication);
        LoanView loan = policyLoanApi.originateLoan(policyNumber, new BigDecimal(request.requestedAmount().amount()),
            request.requestedAmount().currencyCode(), request.payeeRef(), jwt.getSubject());
        // 202, not 200/201: openapi-policyloan.yaml describes this as "Loan reservation
        // confirmed and disbursement requested (async)".
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(LoanResponseDto.from(loan));
    }

    @GetMapping("/policies/{policyNumber}/loans")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<List<LoanResponseDto>> listLoans(@PathVariable String policyNumber, @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        enforceCustomerOwnPolicyOnly(policyNumber, jwt, authentication);
        return ResponseEntity.ok(policyLoanApi.listLoansForPolicy(policyNumber).stream().map(LoanResponseDto::from).toList());
    }

    /**
     * The forced-lapse review queue the interest-accrual sweep fills. STAFF only, and
     * deliberately not exposed to customers or agents: it is an operational worklist across the
     * tenant's whole loan book, not a view of one policy, so the object-level
     * own-policy-only check every other endpoint here applies has nothing to bind to.
     *
     * <p>The literal path segment does not collide with {@code /loans/{loanId}} below: Spring
     * matches a literal pattern ahead of one containing a variable, so this mapping wins and
     * {@code {loanId}} never sees this URL and never tries to parse it as a UUID.
     */
    @GetMapping("/loans/forced-lapse-review-queue")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<List<LoanResponseDto>> listForcedLapseReviewQueue() {
        return ResponseEntity.ok(policyLoanApi.listLoansPendingForcedLapseReview().stream().map(LoanResponseDto::from).toList());
    }

    /**
     * Runs the shortfall test for one loan and lapses the policy if the balance has outgrown its
     * cash value. STAFF only: this can terminate a policy, so it is not something a customer or
     * agent may invoke against their own book. Idempotent -- a healthy loan comes back unchanged.
     *
     * <p>200, not 202: unlike origination and repayment, this completes synchronously. Nothing is
     * requested of an external rail, so there is no async leg to describe.
     */
    @PostMapping("/loans/{loanId}/forced-lapse-evaluation")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<LoanResponseDto> evaluateForcedLapse(@PathVariable UUID loanId) {
        return ResponseEntity.ok(LoanResponseDto.from(policyLoanApi.evaluateForcedLapse(loanId)));
    }

    @GetMapping("/loans/{loanId}")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<LoanResponseDto> getLoan(@PathVariable UUID loanId, @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        LoanView loan = policyLoanApi.getLoan(loanId);
        enforceCustomerOwnPolicyOnly(loan.policyNumber(), jwt, authentication);
        return ResponseEntity.ok(LoanResponseDto.from(loan));
    }

    @PostMapping("/loans/{loanId}/repayments")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<LoanResponseDto> recordRepayment(@PathVariable UUID loanId, @Valid @RequestBody RepaymentRequestDto request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        LoanView existing = policyLoanApi.getLoan(loanId);
        enforceCustomerOwnPolicyOnly(existing.policyNumber(), jwt, authentication);
        LoanView loan = policyLoanApi.recordRepayment(loanId, new BigDecimal(request.amount().amount()), request.amount().currencyCode(),
            request.paymentReference(), jwt.getSubject());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(LoanResponseDto.from(loan));
    }

    /** Mirrors PolicyController.enforceCustomerOwnPolicyOnly exactly (Global Constraints) --
     * reuses PolicyApi.getPolicy rather than duplicating policyholderPartyId into policyloan's
     * own schema. PolicyNotFoundException (404) propagates as-is if the policy doesn't exist or
     * isn't in this caller's tenant (anti-enumeration: policy's own service layer disguises
     * both "doesn't exist" and "exists but not yours" identically at that boundary). */
    private void enforceCustomerOwnPolicyOnly(String policyNumber, Jwt jwt, Authentication authentication) {
        boolean isCustomer = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch("ROLE_REALM_CUSTOMERS"::equals);
        if (isCustomer) {
            PolicyView policy = policyApi.getPolicy(policyNumber);
            String ownPartyId = jwt.getClaimAsString("party_id");
            if (ownPartyId == null || !ownPartyId.equals(policy.policyholderPartyId().toString())) {
                throw new AccessDeniedException("Access denied: customer may only access loans against their own policy");
            }
        }
    }
}
