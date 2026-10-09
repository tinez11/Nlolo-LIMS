package tz.co.nlolo.lifeplatform.claims.infrastructure;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.claims.api.ClaimJourneyApi;
import tz.co.nlolo.lifeplatform.claims.api.ClaimView;
import tz.co.nlolo.lifeplatform.claims.api.ClaimsApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;

import java.util.List;
import java.util.UUID;

/**
 * Documents claims staff ask the claimant for (2026-10-08, the customer portal design step 4). Assessors and managers ask
 * and withdraw; the claimant -- or their agent -- reads them and answers by uploading evidence with the request's id.
 */
@RestController
public class ClaimDocumentRequestController {

    /** Body of a request for a document. */
    public record RequestDocument(String document, String reason) {}

    private final ClaimJourneyApi journeyApi;
    private final ClaimsApi claimsApi;
    private final PolicyApi policyApi;

    public ClaimDocumentRequestController(ClaimJourneyApi journeyApi, ClaimsApi claimsApi, PolicyApi policyApi) {
        this.journeyApi = journeyApi;
        this.claimsApi = claimsApi;
        this.policyApi = policyApi;
    }

    @GetMapping("/claims/{claimId}/document-requests")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<List<ClaimJourneyApi.DocumentRequest>> list(@PathVariable UUID claimId,
            @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        ClaimView claim = claimsApi.getClaim(claimId);
        ClaimController.enforceCustomerOwnClaimOnly(claim, jwt, authentication);
        ClaimController.enforceAgentOwnClaimOnly(policyApi, claim, jwt, authentication);
        return ResponseEntity.ok(journeyApi.documentRequests(claimId));
    }

    @PostMapping("/claims/{claimId}/document-requests")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('CLAIMS_ASSESSOR') or hasRole('CLAIMS_MANAGER'))")
    public ResponseEntity<ClaimJourneyApi.DocumentRequest> request(@PathVariable UUID claimId,
            @RequestBody RequestDocument body, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED).body(journeyApi.requestDocument(claimId, body.document(),
            body.reason(), jwt.getSubject(), ClaimController.displayName(jwt)));
    }

    @PostMapping("/claims/{claimId}/document-requests/{requestId}/withdrawal")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('CLAIMS_ASSESSOR') or hasRole('CLAIMS_MANAGER'))")
    public ResponseEntity<ClaimJourneyApi.DocumentRequest> withdraw(@PathVariable UUID claimId,
                                                                    @PathVariable UUID requestId) {
        return ResponseEntity.ok(journeyApi.withdraw(claimId, requestId));
    }
}
