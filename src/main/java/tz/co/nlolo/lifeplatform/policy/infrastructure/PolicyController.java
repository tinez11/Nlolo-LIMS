package tz.co.nlolo.lifeplatform.policy.infrastructure;

import tz.co.nlolo.lifeplatform.policy.api.*;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.ProductSnapshotView;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class PolicyController {

    private final PolicyApi policyApi;
    private final ProductApi productApi;

    public PolicyController(PolicyApi policyApi, ProductApi productApi) {
        this.policyApi = policyApi;
        this.productApi = productApi;
    }

    @PostMapping("/policies/manual-issue")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<PolicyResponseDto> manualIssue(@Valid @RequestBody ManualIssueRequestDto request, @AuthenticationPrincipal Jwt jwt) {
        ProductSnapshotView snapshot = productApi.getSnapshotByVersionId(request.productVersionId());
        List<PolicyApi.BeneficiaryInput> beneficiaries = request.beneficiaries() != null
            ? request.beneficiaries().stream().map(BeneficiaryInputDto::toApiInput).toList() : List.of();
        PolicyApi.IssueRequest issueRequest = new PolicyApi.IssueRequest(request.policyholderPartyId(), snapshot.productId(), request.productVersionId(),
            new BigDecimal(request.sumAssured().amount()), request.sumAssured().currencyCode(),
            new BigDecimal(request.premiumAmount().amount()), request.premiumAmount().currencyCode(),
            request.premiumFrequency() != null && !request.premiumFrequency().isBlank() ? request.premiumFrequency() : "MONTHLY",
            request.agentOfRecordId(), beneficiaries, request.reasonForManualIssue());
        PolicyView view = policyApi.issuePolicy(request.underwritingCaseId(), issueRequest, jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(PolicyResponseDto.from(view));
    }

    @GetMapping("/policies/{policyNumber}")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<PolicyResponseDto> getPolicy(@PathVariable String policyNumber, @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        // Object-level authorization (docs/04-api-contracts.md §3), same idiom as
        // PartyController.getParty: a customers-realm token may only read the policy matching
        // its own party_id claim (enforceCustomerOwnPolicyOnly below). Agents/staff are scoped
        // by realm role alone here, same as PartyController -- openapi-policy.yaml's description
        // for this operation states agents "are scoped to policies where they are agentOfRecord
        // or within their agency hierarchy," but that hierarchy is not implemented: unlike the
        // party case, PolicyView.agentOfRecordId DOES already exist on this resource, so a
        // direct agentOfRecordId-match (leaving the fuzzier agency-hierarchy part genuinely
        // deferred) is feasible sooner than the party equivalent -- but there is still no
        // agent/agency data model (which agent a caller's JWT corresponds to, or how agencies
        // nest) to resolve "is this caller's agent identity the agentOfRecord" against, so it
        // remains deferred to a later milestone rather than silently skipped. Same gap applies
        // to searchPolicies/getCoverageStatus/isInForce below (all agentsAuth-gated).
        PolicyView view = policyApi.getPolicy(policyNumber);
        enforceCustomerOwnPolicyOnly(view, jwt, authentication);
        return ResponseEntity.ok(PolicyResponseDto.from(view));
    }

    /**
     * Customers are force-SCOPED to their own policies: a client-supplied
     * {@code policyholderPartyId} is OVERRIDDEN with the token's own {@code party_id} claim, never
     * merely checked-then-rejected -- {@code tenant_id}/{@code party_id} never come from client
     * input (docs/04-api-contracts.md:39). Scoping the query itself is the correct enforcement
     * point for a list endpoint, unlike getPolicy/coverage-status which 403 on an explicit mismatch
     * against a resource that already exists. Same idiom as ClaimController.listClaims.
     */
    @GetMapping("/policies")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<PolicySearchResponse> searchPolicies(
            @RequestParam(required = false) UUID policyholderPartyId,
            @RequestParam(required = false) PolicyStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        UUID effectivePolicyholderPartyId = isCustomer(authentication)
            ? ownPartyIdOrThrow(jwt) : policyholderPartyId;
        Page<PolicyView> result = policyApi.searchPolicies(effectivePolicyholderPartyId, status,
            PageRequest.of(page, Math.min(pageSize, 100)));
        return ResponseEntity.ok(PolicySearchResponse.from(result));
    }

    @PostMapping("/policies/{policyNumber}/endorsements")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<PolicyResponseDto> applyEndorsement(@PathVariable String policyNumber, @Valid @RequestBody EndorsementRequestDto request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, @AuthenticationPrincipal Jwt jwt) {
        // Idempotency-Key accepted, not enforced (Global Constraints).
        PolicyView view = policyApi.applyEndorsement(policyNumber,
            new PolicyApi.EndorsementInput(request.endorsementType(), request.effectiveDate(), request.changes()), jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(PolicyResponseDto.from(view));
    }

    @PutMapping("/policies/{policyNumber}/beneficiaries")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<Void> replaceBeneficiaries(@PathVariable String policyNumber,
            @Valid @RequestBody List<BeneficiaryInputDto> beneficiaries, @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        enforceCustomerOwnPolicyOnly(policyApi.getPolicy(policyNumber), jwt, authentication);
        policyApi.replaceBeneficiaries(policyNumber, beneficiaries.stream().map(BeneficiaryInputDto::toApiInput).toList(), jwt.getSubject());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/policies/{policyNumber}/surrender-value")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<Map<String, Object>> getSurrenderValue(@PathVariable String policyNumber, @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        enforceCustomerOwnPolicyOnly(policyApi.getPolicy(policyNumber), jwt, authentication);
        SurrenderQuoteView quote = policyApi.quoteSurrenderValue(policyNumber);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("policyNumber", quote.policyNumber());
        body.put("quotedValue", Map.of("amount", quote.quotedValueAmount().toPlainString(), "currencyCode", quote.quotedValueCurrency()));
        body.put("quotedAt", quote.quotedAt().toString());
        return ResponseEntity.ok(body);
    }

    /**
     * DEFERRED CHOREOGRAPHY (plan header) -- Camunda 7 is EOL, Camunda 8 needs a paid licence
     * and a cross-tenant leak review against this platform's ThreadLocal TenantContext. Real,
     * routable, correctly-secured (matches openapi-policy.yaml's customersAuth/agentsAuth/
     * staffAuth triad exactly) endpoint that returns 501, not 404 and not omitted.
     */
    @PostMapping("/policies/{policyNumber}/surrender")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<ProblemDetail> surrenderPolicy(@PathVariable String policyNumber,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) Map<String, Object> body) {
        return notImplementedChoreography();
    }

    /** Same deferred-choreography seam as surrenderPolicy above -- nothing to poll without a
     * process engine. */
    @GetMapping("/policies/{policyNumber}/processes/{processInstanceId}")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<ProblemDetail> getProcessStatus(@PathVariable String policyNumber, @PathVariable String processInstanceId) {
        return notImplementedChoreography();
    }

    @GetMapping("/policies/{policyNumber}/coverage-status")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<CoverageStatusResponseDto> getCoverageStatus(@PathVariable String policyNumber,
            @RequestParam(required = false) LocalDate asOf,
            @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        enforceCustomerOwnPolicyOnly(policyApi.getPolicy(policyNumber), jwt, authentication);
        return ResponseEntity.ok(CoverageStatusResponseDto.from(policyApi.getCoverageStatus(policyNumber, asOf)));
    }

    @GetMapping("/policies/{policyNumber}/in-force")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<Map<String, Boolean>> isInForce(@PathVariable String policyNumber, @RequestParam(required = false) LocalDate asOf) {
        return ResponseEntity.ok(Map.of("inForce", policyApi.isPolicyInForce(policyNumber, asOf)));
    }

    /**
     * Object-level authorization (Global Constraints) -- mirrors PartyController.getParty's
     * exact structure: realm-membership check via Authentication.getAuthorities(), then
     * jwt.getClaimAsString("party_id") vs. the resource's own party id, then a real 403
     * (AccessDeniedException), NOT a disguised 404 -- that anti-enumeration disguise is for
     * cross-TENANT mismatches only (already handled by findPolicyOrThrow/RLS), a different
     * scoping dimension from this same-tenant ownership check.
     */
    private void enforceCustomerOwnPolicyOnly(PolicyView view, Jwt jwt, Authentication authentication) {
        if (!isCustomer(authentication)) {
            return;
        }
        String ownPartyId = jwt.getClaimAsString("party_id");
        if (ownPartyId == null || !ownPartyId.equals(view.policyholderPartyId().toString())) {
            throw new AccessDeniedException("Access denied: customer may only access their own policy");
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

    private ResponseEntity<ProblemDetail> notImplementedChoreography() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_IMPLEMENTED,
            "Surrender/maturity loan-netting choreography is deferred until a workflow engine is "
            + "chosen (Camunda 7 is EOL; Camunda 8 needs a paid licence and a cross-tenant leak "
            + "review against this platform's ThreadLocal TenantContext) -- this endpoint will be "
            + "implemented alongside that engine, per docs/08-implementation-roadmap.md's M3 framing.");
        problem.setProperty("errorCode", "CHOREOGRAPHY_NOT_IMPLEMENTED");
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(problem);
    }
}
