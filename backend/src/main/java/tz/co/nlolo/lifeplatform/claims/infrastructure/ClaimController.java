package tz.co.nlolo.lifeplatform.claims.infrastructure;

import tz.co.nlolo.lifeplatform.claims.api.ClaimAssessmentView;
import tz.co.nlolo.lifeplatform.claims.api.ClaimStatus;
import tz.co.nlolo.lifeplatform.claims.api.ClaimView;
import tz.co.nlolo.lifeplatform.claims.api.ClaimsApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

/**
 * The seven {@code /claims*} operations {@code ClaimEvidenceController} does not own. Role gates
 * follow docs/04-api-contracts.md:56 and openapi-claims.yaml exactly: register/read are open to
 * all three realms with an object-level ownership check for customers (and, since the agents-
 * realm "browse my book of business" work, for agents too); assessment/settlement-
 * decision/reopen are staff-only, gated on the FINE-GRAINED role name (CLAIMS_ASSESSOR /
 * CLAIMS_MANAGER), not just REALM_STAFF -- mirrors UnderwritingController's existing
 * {@code hasRole('UNDERWRITER')} idiom, since {@code SecurityConfig.authoritiesFor} already maps
 * every {@code realm_access.roles} entry (staff's fine-grained roles included) to its own
 * {@code ROLE_<name>} authority alongside the synthetic {@code ROLE_REALM_STAFF}.
 */
@RestController
public class ClaimController {

    private final ClaimsApi claimsApi;
    private final PolicyApi policyApi;

    public ClaimController(ClaimsApi claimsApi, PolicyApi policyApi) {
        this.claimsApi = claimsApi;
        this.policyApi = policyApi;
    }

    /**
     * Task 9 review fix (Part B): {@code Idempotency-Key} is REQUIRED here, and genuinely
     * enforced -- copies {@code BillingController.requestPaymentForInvoice}'s exact shape.
     * Declared {@code required = false} at the Spring level and rejected explicitly below, so a
     * missing header and a present-but-blank one land on the same {@code ProblemDetails} via one
     * code path, instead of a framework {@code MissingRequestHeaderException} for the first.
     * {@code ClaimsApiImpl.registerClaim} independently re-checks (defense in depth, same
     * reasoning as {@code BillingApiImpl.requestPaymentForInvoice}'s own javadoc: this is a
     * published {@code ClaimsApi} method, reachable by a future non-HTTP caller too), so a caller
     * that bypasses this controller cannot silently skip the check.
     *
     * <p>Object-level authorization: a customers-realm token may only register a claim for
     * ITSELF (docs/04-api-contracts.md:56, "register own") -- enforced by comparing the request's
     * {@code claimantPartyId} against the token's own {@code party_id} claim, same idiom as
     * {@code PolicyController}/{@code BillingController}'s {@code enforceCustomerOwnPolicyOnly}.
     * Not explicitly called out in this task's brief (which only lists the ownership check on the
     * read endpoints), but the platform's own mandate -- "object-level authorization... is
     * mandatory on every customer- and agent-scoped endpoint" (docs/04-api-contracts.md:41) --
     * applies here too: without it, any customer token could file a claim naming a DIFFERENT
     * party as claimant.
     */
    @PostMapping("/claims")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<ClaimResponseDto> registerClaim(@Valid @RequestBody RegisterClaimRequestDto request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required to register a claim: the "
                + "same key is treated as the same registration attempt (deduplicated), a new key as a new "
                + "attempt. There is deliberately no default -- any default would make claims/V3's dedup "
                + "index meaningless.");
        }
        enforceCustomerOwnClaimantOnly(request.claimantPartyId(), jwt, authentication);

        ClaimsApi.RegisterClaimRequest apiRequest = new ClaimsApi.RegisterClaimRequest(request.policyNumber(),
            request.claimantPartyId(), request.claimType(), request.dateOfEvent(), request.details());
        ClaimView view = claimsApi.registerClaim(apiRequest, idempotencyKey, jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(ClaimResponseDto.from(view));
    }

    @GetMapping("/claims/{claimId}")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<ClaimResponseDto> getClaim(@PathVariable UUID claimId,
            @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        ClaimView view = claimsApi.getClaim(claimId);
        enforceCustomerOwnClaimOnly(view, jwt, authentication);
        enforceAgentOwnClaimOnly(policyApi, view, jwt, authentication);
        return ResponseEntity.ok(ClaimResponseDto.from(view));
    }

    /**
     * Customers are force-SCOPED to their own claims: any client-supplied {@code claimantPartyId}
     * is OVERRIDDEN with the token's own {@code party_id} claim, never merely checked-then-
     * rejected -- {@code tenant_id}/{@code party_id} never come from client input
     * (docs/04-api-contracts.md:39). There is no single claim resource to 403 on for a list
     * endpoint, so scoping the query itself is the correct enforcement point, unlike
     * {@code getClaim}/evidence above and below which 403 on an explicit mismatch against a
     * resource that already exists. Agents/staff may filter by any {@code claimantPartyId} (or
     * none, per docs/04:56's "register/read scoped").
     */
    @GetMapping("/claims")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<ClaimSearchResponseDto> listClaims(
            @RequestParam(required = false) ClaimStatus status,
            @RequestParam(required = false) UUID claimantPartyId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        UUID effectiveClaimantPartyId = isCustomer(authentication) ? ownPartyIdOrThrow(jwt) : claimantPartyId;
        // Agents are force-scoped to claims filed against a policy in their own hierarchy team's
        // book -- same "override the query" idiom as the customer scoping above. Joins through
        // policy (PolicyApi.policyNumbersForAgentTeam) rather than claims needing its own
        // distribution dependency, since a claim carries no agentOfRecordId of its own.
        Set<String> policyNumbers = isAgent(authentication)
            ? policyApi.policyNumbersForAgentTeam(ownAgentPartyIdOrThrow(jwt)) : null;
        Page<ClaimView> result = claimsApi.searchClaims(status, effectiveClaimantPartyId, policyNumbers, q,
            PageRequest.of(page, Math.min(pageSize, 100), Sort.by(Sort.Direction.DESC, "createdAt")));
        return ResponseEntity.ok(ClaimSearchResponseDto.from(result));
    }

    @PostMapping("/claims/{claimId}/assessments")
    @PreAuthorize("hasRole('CLAIMS_ASSESSOR')")
    public ResponseEntity<ClaimAssessmentResponseDto> submitAssessment(@PathVariable UUID claimId,
            @Valid @RequestBody SubmitClaimAssessmentRequestDto request, @AuthenticationPrincipal Jwt jwt) {
        ClaimAssessmentView assessment = claimsApi.submitAssessment(claimId, request.findings(),
            new BigDecimal(request.recommendedAmount().amount()), request.recommendedAmount().currencyCode(),
            request.fraudIndicator(), jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(ClaimAssessmentResponseDto.from(assessment));
    }

    /**
     * 202, not 200 -- settlement completes asynchronously via {@code payment}'s request/confirm
     * loop once approved (openapi-claims.yaml). {@code CLAIMS_MANAGER} only, deliberately distinct
     * from {@code CLAIMS_ASSESSOR} -- a basic separation-of-duties control
     * (docs/04-api-contracts.md:44), reinforced by {@code ClaimsApiImpl}'s own same-person check.
     * {@code Idempotency-Key} is accepted and forwarded (it is the value that becomes
     * {@code claim.settlement_idempotency_key} on approval) but not rejected here for being
     * absent on a REJECTION, since {@code decideSettlement} only requires it when
     * {@code approved} is {@code true} -- already enforced, with the correct 422, by
     * {@code ClaimsApiImpl} itself.
     */
    @PostMapping("/claims/{claimId}/settlement-decision")
    @PreAuthorize("hasRole('CLAIMS_MANAGER')")
    public ResponseEntity<ClaimResponseDto> decideSettlement(@PathVariable UUID claimId,
            @Valid @RequestBody SettlementDecisionRequestDto request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        BigDecimal approvedAmount = request.approvedAmount() != null
            ? new BigDecimal(request.approvedAmount().amount()) : null;
        String approvedCurrency = request.approvedAmount() != null ? request.approvedAmount().currencyCode() : null;
        claimsApi.decideSettlement(claimId, request.approved(), approvedAmount, approvedCurrency,
            request.rejectionReason(), request.payeeRef(), idempotencyKey, jwt.getSubject());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ClaimResponseDto.from(claimsApi.getClaim(claimId)));
    }

    @PostMapping("/claims/{claimId}/reopen")
    @PreAuthorize("hasRole('CLAIMS_MANAGER')")
    public ResponseEntity<ClaimResponseDto> reopenClaim(@PathVariable UUID claimId,
            @Valid @RequestBody ReopenClaimRequestDto request, @AuthenticationPrincipal Jwt jwt) {
        claimsApi.reopenClaim(claimId, request.reason(), jwt.getSubject());
        return ResponseEntity.ok(ClaimResponseDto.from(claimsApi.getClaim(claimId)));
    }

    /** Mirrors BillingController/PolicyController's exact {@code enforceCustomerOwnPolicyOnly}
     * idiom, applied to {@code claimantPartyId} instead of {@code policyholderPartyId}. Package-
     * visible (not private) so {@code ClaimEvidenceController} can reuse it verbatim rather than
     * risking the two copies drifting apart. */
    static void enforceCustomerOwnClaimOnly(ClaimView claim, Jwt jwt, Authentication authentication) {
        if (!isCustomer(authentication)) {
            return;
        }
        String ownPartyId = jwt.getClaimAsString("party_id");
        if (ownPartyId == null || !ownPartyId.equals(claim.claimantPartyId().toString())) {
            throw new AccessDeniedException("Customer may only access their own claim");
        }
    }

    private static void enforceCustomerOwnClaimantOnly(UUID claimantPartyId, Jwt jwt, Authentication authentication) {
        if (!isCustomer(authentication)) {
            return;
        }
        String ownPartyId = jwt.getClaimAsString("party_id");
        if (ownPartyId == null || !ownPartyId.equals(claimantPartyId.toString())) {
            throw new AccessDeniedException("A customer may only register a claim for themselves");
        }
    }

    static boolean isCustomer(Authentication authentication) {
        return authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority).anyMatch("ROLE_REALM_CUSTOMERS"::equals);
    }

    static UUID ownPartyIdOrThrow(Jwt jwt) {
        String ownPartyId = jwt.getClaimAsString("party_id");
        if (ownPartyId == null) {
            throw new AccessDeniedException("Customer token carries no party_id claim");
        }
        return UUID.fromString(ownPartyId);
    }

    static boolean isAgent(Authentication authentication) {
        return authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority).anyMatch("ROLE_REALM_AGENTS"::equals);
    }

    private static UUID ownAgentPartyIdOrThrow(Jwt jwt) {
        String ownPartyId = jwt.getClaimAsString("party_id");
        if (ownPartyId == null) {
            throw new AccessDeniedException("Agent token carries no party_id claim");
        }
        return UUID.fromString(ownPartyId);
    }

    /**
     * Object-level authorization for agents, mirroring {@link #enforceCustomerOwnClaimOnly}: an
     * agents-realm token may only read a claim filed against a policy in its own hierarchy team's
     * book of business. Re-resolves the team via {@code PolicyApi.policyNumbersForAgentTeam} on
     * every call rather than caching it -- a single-claim read, unlike {@link #listClaims}, has no
     * shared per-request scope to reuse it from, and this join is a plain indexed lookup, not
     * expensive enough to warrant one.
     *
     * <p>Static and package-visible, {@code policyApi} passed explicitly, same reason
     * {@link #enforceCustomerOwnClaimOnly} is: {@code ClaimEvidenceController} reuses this
     * verbatim rather than risking two copies drifting apart -- an agents-realm token could
     * otherwise attach/list/download evidence on any claim in the tenant, not just its own book.
     */
    static void enforceAgentOwnClaimOnly(PolicyApi policyApi, ClaimView claim, Jwt jwt, Authentication authentication) {
        if (!isAgent(authentication)) {
            return;
        }
        if (!policyApi.policyNumbersForAgentTeam(ownAgentPartyIdOrThrow(jwt)).contains(claim.policyNumber())) {
            throw new AccessDeniedException("Access denied: agent may only access claims in their own book of business");
        }
    }
}
