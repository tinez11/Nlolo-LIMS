package tz.co.nlolo.lifeplatform.omnichannel.infrastructure;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.omnichannel.api.CustomerDashboardView;
import tz.co.nlolo.lifeplatform.omnichannel.api.CustomerPolicyView;
import tz.co.nlolo.lifeplatform.omnichannel.application.CustomerPortal;

import java.util.UUID;

/**
 * The customer portal's own reads (2026-10-08, the customer portal design step 2). Customers only, and always as the
 * token's party: there is no id in these paths that could name somebody else.
 */
@RestController
public class CustomerPortalController {

    private final CustomerPortal portal;
    private final tz.co.nlolo.lifeplatform.omnichannel.application.CustomerClaims customerClaims;

    public CustomerPortalController(CustomerPortal portal,
                                    tz.co.nlolo.lifeplatform.omnichannel.application.CustomerClaims customerClaims) {
        this.portal = portal;
        this.customerClaims = customerClaims;
    }

    @GetMapping("/customer/dashboard")
    @PreAuthorize("hasRole('REALM_CUSTOMERS')")
    public ResponseEntity<CustomerDashboardView> dashboard(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(portal.dashboard(customer(jwt)));
    }

    @GetMapping("/customer/policies/{policyNumber}")
    @PreAuthorize("hasRole('REALM_CUSTOMERS')")
    public ResponseEntity<CustomerPolicyView> policy(@PathVariable String policyNumber, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(portal.policy(customer(jwt), policyNumber));
    }

    /** Money movement step 5: the policy's balance statement, newest first, a page at a time. */
    @GetMapping("/customer/policies/{policyNumber}/statement")
    @PreAuthorize("hasRole('REALM_CUSTOMERS')")
    public ResponseEntity<tz.co.nlolo.lifeplatform.omnichannel.api.CustomerStatementView> statement(
            @PathVariable String policyNumber,
            @RequestParam(defaultValue = "0") @jakarta.validation.constraints.Min(0) int page,
            @RequestParam(defaultValue = "25") @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(100) int size,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(portal.statement(customer(jwt), policyNumber, page, size));
    }

    public record WithdrawalAsk(@jakarta.validation.constraints.NotBlank
                                @jakarta.validation.constraints.Pattern(regexp = "^\\d+(\\.\\d{1,2})?$") String amount,
                                @jakarta.validation.constraints.NotBlank
                                @jakarta.validation.constraints.Size(max = 30) String phone) {}

    /** Money movement step 5: the customer asks for money back off the balance to their phone; staff approve it. */
    @PostMapping("/customer/policies/{policyNumber}/withdrawals")
    @PreAuthorize("hasRole('REALM_CUSTOMERS')")
    public ResponseEntity<java.util.Map<String, UUID>> requestWithdrawal(@PathVariable String policyNumber,
            @jakarta.validation.Valid @RequestBody WithdrawalAsk body, @AuthenticationPrincipal Jwt jwt) {
        UUID id = portal.requestWithdrawal(customer(jwt), policyNumber, new java.math.BigDecimal(body.amount()),
            body.phone().trim(), jwt.getSubject());
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED).body(java.util.Map.of("withdrawalId", id));
    }

    /** Step 4: the claims the customer made, newest event first, each with how many documents are still asked for. */
    @GetMapping("/customer/claims")
    @PreAuthorize("hasRole('REALM_CUSTOMERS')")
    public ResponseEntity<java.util.List<tz.co.nlolo.lifeplatform.omnichannel.api.CustomerClaimView.Summary>> claims(
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(customerClaims.claims(customer(jwt)));
    }

    /** Step 4: one of the customer's claims; 403 for a claim somebody else made. */
    @GetMapping("/customer/claims/{claimId}")
    @PreAuthorize("hasRole('REALM_CUSTOMERS')")
    public ResponseEntity<tz.co.nlolo.lifeplatform.omnichannel.api.CustomerClaimView> claim(@PathVariable UUID claimId,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(customerClaims.claim(customer(jwt), claimId));
    }

    static UUID customer(Jwt jwt) {
        String claim = jwt.getClaimAsString("party_id");
        if (claim == null) {
            throw new AccessDeniedException("Customer token carries no party_id claim");
        }
        return UUID.fromString(claim);
    }
}
