package tz.co.nlolo.lifeplatform.omnichannel.infrastructure;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    public CustomerPortalController(CustomerPortal portal) {
        this.portal = portal;
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

    static UUID customer(Jwt jwt) {
        String claim = jwt.getClaimAsString("party_id");
        if (claim == null) {
            throw new AccessDeniedException("Customer token carries no party_id claim");
        }
        return UUID.fromString(claim);
    }
}
