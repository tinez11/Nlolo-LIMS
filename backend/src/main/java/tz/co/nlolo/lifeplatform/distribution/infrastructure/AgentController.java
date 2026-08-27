package tz.co.nlolo.lifeplatform.distribution.infrastructure;

import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.distribution.api.AgentNotFoundException;
import tz.co.nlolo.lifeplatform.distribution.api.AgentView;
import tz.co.nlolo.lifeplatform.distribution.api.CommissionPlanView;
import tz.co.nlolo.lifeplatform.distribution.api.CommissionStatementNotFoundException;
import tz.co.nlolo.lifeplatform.distribution.api.CommissionStatementView;
import tz.co.nlolo.lifeplatform.distribution.api.DistributionApi;
import tz.co.nlolo.lifeplatform.distribution.api.LicenseStatus;
import tz.co.nlolo.lifeplatform.distribution.domain.AgentProfile;
import tz.co.nlolo.lifeplatform.distribution.domain.CommissionCalculator;
import jakarta.validation.Valid;
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

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * The {@code /agents*} surface. Role gates follow docs/04-api-contracts.md:57, whose matrix row is
 * {@code | distribution | — | read own commission/plan | onboard, administer | — |}.
 *
 * <p><b>Two recorded decisions, because the spec constrains less than it appears to.</b>
 *
 * <p>(1) <b>FINANCE_OFFICER/ADMIN gate the write endpoints.</b> There is no {@code AGENCY_MANAGER}
 * role in the staff realm -- the six that exist are UNDERWRITER, CLAIMS_ASSESSOR, CLAIMS_MANAGER,
 * FINANCE_OFFICER, CUSTOMER_SERVICE_REP, ADMIN -- and openapi-distribution.yaml's bare
 * {@code staffAuth: []} names none of them, so "onboard, administer" had to be mapped to something.
 * Commission is money owed to agents, which makes FINANCE_OFFICER the closest existing fit, with
 * ADMIN as the universal escape hatch. Gated on the fine-grained role name, following
 * {@code UnderwritingController}'s {@code hasRole('UNDERWRITER')} idiom.
 *
 * <p>(2) <b>Supervisor access is decided by the HIERARCHY ITSELF, not by a separate scope.</b>
 * openapi-distribution.yaml declares {@code agentsAuth: [agent, supervisor]}, but
 * {@code keycloak/agents-realm.json} defines NO roles whatsoever, so there is no {@code supervisor}
 * role to hold -- requiring one would make descendant reads permanently 403 for every real token
 * until someone invents realm config with no business input behind it. docs/04-api-contracts.md:43
 * says an agents token is scoped to "policies where the agent is agentOfRecord, or within their
 * agency hierarchy for supervisors", and being a supervisor of X IS being above X in the hierarchy.
 * So {@link #resolveAgentAccess} authorises on ancestry. Flagged for the final review: if the
 * business wants supervision to require an explicit grant rather than follow from the org chart,
 * this becomes a realm role plus an extra {@code hasRole} conjunct here.
 *
 * <p><b>404 for cross-tenant, 403 for a same-tenant mismatch.</b> Every read resolves the agent
 * through {@code DistributionApi} FIRST -- whose lookups are tenant-scoped, so another tenant's
 * agentId throws {@code AgentNotFoundException} (404) -- and only then applies the ownership check
 * (403). Getting that order wrong would leak the existence of other tenants' agents, the same
 * distinction {@code PolicyContractTest} already pins for policy.
 */
@RestController
public class AgentController {

    /** Two levels is what the tier vocabulary supports (OVERRIDE, SUPERVISOR_OVERRIDE), so a
     * supervisor's readable downline is exactly the population they can earn override commission
     * on. Reusing {@link CommissionCalculator#resolveAncestorIds}' cycle-safe walk rather than a
     * second hand-rolled one matters: {@code agent_profile.hierarchy_parent_id} is a self-FK with
     * NO cycle constraint in the DDL, so an unguarded walk would not terminate. */
    private final DistributionApi distributionApi;
    private final AgentProfileRepository agentProfileRepository;

    public AgentController(DistributionApi distributionApi, AgentProfileRepository agentProfileRepository) {
        this.distributionApi = distributionApi;
        this.agentProfileRepository = agentProfileRepository;
    }

    /**
     * {@code Idempotency-Key} is required and genuinely enforced, copying
     * {@code ClaimController.registerClaim}'s shape: declared {@code required = false} at the
     * Spring level and rejected explicitly here, so a missing header and a present-but-blank one
     * land on one {@code ProblemDetails} path instead of a framework
     * {@code MissingRequestHeaderException} for the first only.
     *
     * <p>Onboarding creates an identity, and a retried POST that silently created a second agent
     * for the same party would corrupt the hierarchy. Note the honest limit, matching the
     * platform-wide posture: the key is REQUIRED but not yet a dedup registry here --
     * {@code ux_agent_license} on {@code (tenant_id, license_number)} is what actually prevents
     * the duplicate, surfacing as a clean 422 rather than a 500 (pinned by
     * DistributionApiIntegrationTest).
     */
    @PostMapping("/agents")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<AgentResponseDto> onboardAgent(@Valid @RequestBody OnboardAgentRequestDto request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        requireIdempotencyKey(idempotencyKey);
        AgentView view = distributionApi.onboardAgent(new DistributionApi.OnboardAgentRequest(
            request.partyId(), request.licenseNumber(), request.licenseExpiryDate(), request.hierarchyParentId()),
            jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(AgentResponseDto.from(view));
    }

    /**
     * The agents list. STAFF ONLY, unlike the per-agent read below.
     *
     * `GET /agents/{agentId}` can safely admit an agents-realm token because
     * `enforceAgentReadAccess` checks that caller's ownership or hierarchy for that
     * one agent. A list has no such per-row check to make: no agent-of-record or
     * book-of-business scoping exists on AgentProfile, so an agents-realm caller
     * would enumerate every agent in the tenant. That is a new disclosure, not a
     * convenience -- the same reasoning that kept `GET /underwriting/cases`
     * staff-only.
     *
     * `q` matches licence number; an agent has no name in this module.
     */
    @GetMapping("/agents")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<AgentSearchResponse> listAgents(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) LicenseStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok(AgentSearchResponse.from(distributionApi.listAgents(q, status,
            org.springframework.data.domain.PageRequest.of(page, Math.min(pageSize, 100),
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.ASC, "licenseNumber")))));
    }

    @GetMapping("/agents/{agentId}")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<AgentResponseDto> getAgent(@PathVariable UUID agentId,
            @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        AgentView view = distributionApi.getAgent(agentId);   // 404s cross-tenant, before any 403
        enforceAgentReadAccess(agentId, jwt, authentication);
        return ResponseEntity.ok(AgentResponseDto.from(view));
    }

    /**
     * There was no way for an agents-realm token to discover its own {@code agentId} at all:
     * {@code GET /agents/{agentId}} requires already knowing the id, there is no {@code GET
     * /agents} list, and the token carries {@code party_id}, never an {@code agentId} claim.
     * {@link #enforceAgentReadAccess} already does this exact {@code party_id -> AgentProfile}
     * resolution internally, but only to authorize a request that names an id -- it never handed
     * the resolved id back. This exposes that same resolution as a real lookup (found closing out
     * the agent-realm readiness review, 2026-08-25).
     */
    @GetMapping("/agents/me")
    @PreAuthorize("hasRole('REALM_AGENTS')")
    public ResponseEntity<AgentResponseDto> getOwnAgentProfile(@AuthenticationPrincipal Jwt jwt) {
        UUID agentId = resolveOwnAgentId(jwt);
        AgentView view = distributionApi.getAgent(agentId);
        return ResponseEntity.ok(AgentResponseDto.from(view));
    }

    /**
     * A real controller mapping for a domain setter (`AgentProfile.setLicenseStatus`) that has
     * existed since M7 with no caller anywhere on the platform -- staff had no way, even via
     * curl, to suspend an agent. Gated the same as onboarding (FINANCE_OFFICER/ADMIN); a bad
     * transition 409s via InvalidAgentStateException, already mapped by DistributionExceptionHandler.
     */
    @PostMapping("/agents/{agentId}/suspend")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<AgentResponseDto> suspendAgent(@PathVariable UUID agentId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(AgentResponseDto.from(distributionApi.suspendAgent(agentId, jwt.getSubject())));
    }

    /** Same real-gap fix as {@link #suspendAgent} -- SUSPENDED -> ACTIVE only. */
    @PostMapping("/agents/{agentId}/reactivate")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<AgentResponseDto> reactivateAgent(@PathVariable UUID agentId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(AgentResponseDto.from(distributionApi.reactivateAgent(agentId, jwt.getSubject())));
    }

    @GetMapping("/agents/{agentId}/commission-plan")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<CommissionPlanResponseDto> getApplicablePlan(@PathVariable UUID agentId,
            @RequestParam UUID productId, @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        distributionApi.getAgent(agentId);
        enforceAgentReadAccess(agentId, jwt, authentication);
        CommissionPlanView view = distributionApi.getApplicablePlan(agentId, productId);
        return ResponseEntity.ok(CommissionPlanResponseDto.from(view));
    }

    @GetMapping("/agents/{agentId}/commission-statements")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<List<CommissionStatementResponseDto>> listStatements(@PathVariable UUID agentId,
            @RequestParam(required = false) String period,
            @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        distributionApi.getAgent(agentId);
        enforceAgentReadAccess(agentId, jwt, authentication);
        return ResponseEntity.ok(distributionApi.listStatements(agentId, period).stream()
            .map(CommissionStatementResponseDto::from).toList());
    }

    /**
     * <b>Security fix, found during the whole-branch review, before merge.</b> {@code
     * DistributionApi.listAccruals(UUID statementId)} takes no {@code agentId} at all -- it is a
     * pure statement-to-accruals lookup, correctly tenant-scoped but agent-agnostic by design (see
     * its own javadoc). The URL nests {@code statementId} under {@code agentId}, but nesting in a
     * path is not authorization: checking only that the CALLER may read {@code agentId} and then
     * trusting {@code statementId} without verifying it actually belongs to that same agent is a
     * same-tenant IDOR -- any agent (or its supervisor, or any staff token) reading their OWN
     * legitimately-accessible record could pair it with a DIFFERENT agent's real statement id and
     * read that agent's commission line items: policy number, tier, amount.
     *
     * <p>Fixed by resolving {@code agentId}'s OWN statements (already tenant- and agent-scoped)
     * and requiring {@code statementId} to be among them before ever calling {@code
     * listAccruals}. A mismatch 404s -- matching this platform's convention that a resource
     * belonging to someone else is reported as not found under the path implying it is yours,
     * rather than confirming its existence with a 403.
     */
    @GetMapping("/agents/{agentId}/commission-statements/{statementId}/accruals")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<List<CommissionAccrualResponseDto>> listAccruals(@PathVariable UUID agentId,
            @PathVariable UUID statementId, @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        distributionApi.getAgent(agentId);
        enforceAgentReadAccess(agentId, jwt, authentication);
        boolean statementBelongsToAgent = distributionApi.listStatements(agentId, null).stream()
            .anyMatch(s -> s.statementId().equals(statementId));
        if (!statementBelongsToAgent) {
            throw new CommissionStatementNotFoundException(
                "Commission statement " + statementId + " not found for agent " + agentId);
        }
        return ResponseEntity.ok(distributionApi.listAccruals(statementId).stream()
            .map(CommissionAccrualResponseDto::from).toList());
    }

    /**
     * 202, not 201 or 200: the payout is REQUESTED here and settles asynchronously through
     * {@code payment}'s request/confirm loop, so no resource is complete when this returns. The
     * statement moves CLOSED (or PAYOUT_FAILED) -> PAYOUT_REQUESTED synchronously; PAID arrives
     * later via {@code payment.DisbursementCompleted}.
     *
     * <p>A retry after a failed payout MUST use a NEW {@code Idempotency-Key}: {@code payment}
     * dedupes on it and would silently drop a resubmission carrying the old one.
     */
    /**
     * <b>Consistency fix, same shape as {@link #listAccruals}'s security fix above.</b> {@code
     * DistributionApi.requestStatementPayout} also takes no {@code agentId} -- it acts purely on
     * {@code statementId}. This is not the same class of defect as {@code listAccruals}'s (this
     * endpoint is FINANCE_OFFICER/ADMIN-only, and staff are already authorized to pay out ANY
     * agent's statement in the tenant by design, so a mismatched path leaks nothing an authorized
     * caller could not already do directly), but leaving the URL's implied agentId/statementId
     * pairing unenforced is still a real operator-safety gap: a fat-fingered {@code agentId} in a
     * UI would silently pay out a DIFFERENT agent's statement with no warning, since nothing
     * confirms the path's two ids actually go together. Fixed identically -- verify the statement
     * is among the named agent's own before acting, 404 on a mismatch.
     */
    @PostMapping("/agents/{agentId}/commission-statements/{statementId}/payout")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<Void> requestPayout(@PathVariable UUID agentId, @PathVariable UUID statementId,
            @Valid @RequestBody RequestPayoutRequestDto request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        requireIdempotencyKey(idempotencyKey);
        distributionApi.getAgent(agentId);
        boolean statementBelongsToAgent = distributionApi.listStatements(agentId, null).stream()
            .anyMatch(s -> s.statementId().equals(statementId));
        if (!statementBelongsToAgent) {
            throw new CommissionStatementNotFoundException(
                "Commission statement " + statementId + " not found for agent " + agentId);
        }
        distributionApi.requestStatementPayout(statementId, request.payeeRef(), idempotencyKey, jwt.getSubject());
        return ResponseEntity.accepted().build();
    }

    private static void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required: the same key is treated as "
                + "the same attempt and deduplicated by payment, a new key as a genuinely new attempt. There is "
                + "deliberately no default -- any default would make that dedup meaningless.");
        }
    }

    /**
     * Staff read anyone. An agents-realm token reads itself, or an agent below it in the
     * hierarchy. Anything else is 403.
     *
     * <p>The caller is resolved from the token's {@code party_id} claim rather than trusted from
     * the path -- {@code tenant_id}/{@code party_id} never come from client input
     * (docs/04-api-contracts.md:39). {@code findByTenantIdAndPartyId} returns a list because
     * nothing constrains one party to one agent profile (the unique index is on
     * {@code (tenant_id, license_number)}), so ANY profile the caller owns granting access is
     * sufficient.
     */
    private void enforceAgentReadAccess(UUID requestedAgentId, Jwt jwt, Authentication authentication) {
        if (hasAuthority(authentication, "ROLE_REALM_STAFF")) {
            return;
        }
        UUID tenantId = TenantContext.get();
        String partyIdClaim = jwt.getClaimAsString("party_id");
        if (partyIdClaim == null) {
            throw new AccessDeniedException("Agent token carries no party_id claim");
        }
        List<AgentProfile> callerProfiles = agentProfileRepository
            .findByTenantIdAndPartyId(tenantId, UUID.fromString(partyIdClaim));
        if (callerProfiles.isEmpty()) {
            throw new AccessDeniedException("Token's party is not an agent in this tenant");
        }
        if (callerProfiles.stream().anyMatch(p -> p.getAgentId().equals(requestedAgentId))) {
            return;
        }
        // Ancestry check, walking UP from the REQUESTED agent: if the caller appears among its
        // ancestors, the caller supervises it. Walking up is both cheaper than enumerating a
        // downline and reuses the calculator's existing depth-capped, cycle-safe walk verbatim.
        Function<UUID, UUID> parentOf = id -> agentProfileRepository.findByAgentIdAndTenantId(id, tenantId)
            .map(AgentProfile::getHierarchyParentId)
            .orElse(null);
        List<UUID> ancestors = CommissionCalculator.resolveAncestorIds(requestedAgentId, parentOf);
        if (callerProfiles.stream().anyMatch(p -> ancestors.contains(p.getAgentId()))) {
            return;
        }
        throw new AccessDeniedException("Agent may only read its own record or one below it in its hierarchy");
    }

    /**
     * Same {@code party_id -> AgentProfile} resolution as {@link #enforceAgentReadAccess}, but
     * returning the id instead of merely authorizing against one supplied by the caller.
     * {@code findByTenantIdAndPartyId} can return more than one profile for a party (no DB
     * constraint limits a party to a single agent record) -- an ACTIVE one is preferred if any
     * exists, since that is the license a logged-in agent would actually expect to land on;
     * otherwise the first one found stands in rather than 404ing a party that IS an agent, just
     * not an active one right now.
     */
    private UUID resolveOwnAgentId(Jwt jwt) {
        UUID tenantId = TenantContext.get();
        String partyIdClaim = jwt.getClaimAsString("party_id");
        if (partyIdClaim == null) {
            throw new AccessDeniedException("Agent token carries no party_id claim");
        }
        List<AgentProfile> callerProfiles = agentProfileRepository
            .findByTenantIdAndPartyId(tenantId, UUID.fromString(partyIdClaim));
        if (callerProfiles.isEmpty()) {
            throw new AgentNotFoundException("Token's party is not an agent in this tenant");
        }
        return callerProfiles.stream()
            .filter(p -> p.getLicenseStatus() == LicenseStatus.ACTIVE)
            .findFirst()
            .orElse(callerProfiles.get(0))
            .getAgentId();
    }

    private static boolean hasAuthority(Authentication authentication, String authority) {
        return authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority).anyMatch(authority::equals);
    }
}
