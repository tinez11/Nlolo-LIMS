package tz.co.nlolo.lifeplatform.policy.infrastructure;

import tz.co.nlolo.lifeplatform.TokenNames;
import tz.co.nlolo.lifeplatform.distribution.api.DistributionApi;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.policy.api.*;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.ProductCategory;
import tz.co.nlolo.lifeplatform.product.api.ProductSnapshotView;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
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
import java.util.Set;
import java.util.UUID;

@RestController
public class PolicyController {

    private final PolicyApi policyApi;
    private final ProductApi productApi;
    private final DistributionApi distributionApi;
    /** Answers whether an agents-realm caller registered the party it is asking about. */
    private final PartyApi partyApi;

    public PolicyController(PolicyApi policyApi, ProductApi productApi, DistributionApi distributionApi,
                             PartyApi partyApi) {
        this.policyApi = policyApi;
        this.productApi = productApi;
        this.distributionApi = distributionApi;
        this.partyApi = partyApi;
    }

    /**
     * Every policy currently naming this party as a beneficiary — "which policies pay out to this
     * person", which nothing on the platform could answer before.
     *
     * <p>Its own resource rather than {@code /policies/beneficiaries}, which would sit underneath
     * {@code GET /policies/{policyNumber}} and rely on Spring preferring a literal segment over a
     * path variable. Correct today, but a routing precedence rule is a poor thing to rest an
     * endpoint on.
     *
     * <p>Scoping mirrors the party-detail read exactly, because this answers a question ABOUT a
     * person: an agents-realm caller may ask only about a client it registered. Without that, an
     * agent could enumerate any party's beneficiary exposure — arguably more sensitive than the
     * contact details {@code GET /parties/{id}} guards, since it reveals who stands to be paid on
     * someone else's contract. Customers are excluded entirely: a customer's own exposure is a
     * reasonable thing to show them, but it is not this screen and has not been designed.
     */
    @GetMapping("/beneficiaries")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<List<BeneficiaryOfResponseDto>> beneficiaryOf(
            @RequestParam UUID partyId, @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        if (isAgent(authentication) && !partyApi.isRegisteredBy(partyId, jwt.getSubject())) {
            throw new AccessDeniedException("Access denied: agent may only read a client they registered");
        }
        return ResponseEntity.ok(policyApi.beneficiaryOf(partyId).stream()
            .map(BeneficiaryOfResponseDto::from).toList());
    }

    @PostMapping("/policies/manual-issue")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<PolicyResponseDto> manualIssue(@Valid @RequestBody ManualIssueRequestDto request, @AuthenticationPrincipal Jwt jwt) {
        ProductSnapshotView snapshot = productApi.getSnapshotByVersionId(request.productVersionId());
        // The backstop to underwriting's own refusal at openCase: a case opened before that
        // check existed can still carry a group product, and this screen must not finish the job.
        if (snapshot.category() == ProductCategory.GROUP_LIFE || snapshot.category() == ProductCategory.CREDIT_LIFE) {
            throw new NotASingleLifeProductException(snapshot.category().name());
        }
        // A funeral plan covers a FAMILY, recorded on its case; issued by hand it would be a policy
        // covering nobody but the main member, priced at whatever premium was typed.
        if (snapshot.category() == ProductCategory.FUNERAL) {
            throw new InvalidPolicyStateException("A funeral plan is issued from its underwriting case, which records"
                + " the family and prices it; it cannot be issued by hand");
        }
        List<PolicyApi.BeneficiaryInput> beneficiaries = request.beneficiaries() != null
            ? request.beneficiaries().stream().map(BeneficiaryInputDto::toApiInput).toList() : List.of();
        PolicyApi.IssueRequest issueRequest = new PolicyApi.IssueRequest(request.policyholderPartyId(), snapshot.productId(), request.productVersionId(),
            new BigDecimal(request.sumAssured().amount()), request.sumAssured().currencyCode(),
            new BigDecimal(request.premiumAmount().amount()), request.premiumAmount().currencyCode(),
            request.premiumFrequency() != null && !request.premiumFrequency().isBlank() ? request.premiumFrequency() : "MONTHLY",
            request.agentOfRecordId(), beneficiaries, request.reasonForManualIssue(),
            request.commencementDate(), request.policyTermMonths(), request.premiumPayingTermMonths(),
            request.lifeAssuredPartyId(),
            // The one caller that passes a non-null basis. Everything else reaching issuePolicy
            // -- the underwriting-decision listener, group schemes -- is ordinary new business
            // and waits for its first premium.
            request.issuanceBasis());
        PolicyView view = policyApi.issuePolicy(request.underwritingCaseId(), issueRequest, jwt.getSubject(),
            TokenNames.displayName(jwt));
        return ResponseEntity.status(HttpStatus.CREATED).body(PolicyResponseDto.from(view));
    }

    @GetMapping("/policies/{policyNumber}")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<PolicyResponseDto> getPolicy(@PathVariable String policyNumber, @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        // Object-level authorization (docs/04-api-contracts.md §3), same idiom as
        // PartyController.getParty: a customers-realm token may only read the policy matching
        // its own party_id claim (enforceCustomerOwnPolicyOnly below). Agents are now scoped to
        // their own hierarchy team's agentOfRecordId too (enforceAgentOwnTeamOnly) -- this was
        // the exact gap this comment used to describe as deferred ("no agent/agency data model...
        // to resolve 'is this caller's agent identity the agentOfRecord' against"), closed via
        // DistributionApi.resolveAgentTeam once policy::api gained a distribution dependency for
        // the agents-realm "browse my book of business" work. Staff remain scoped by realm role
        // alone. getCoverageStatus/isInForce are NOT wired to either check -- neither has an HTTP
        // mapping on this controller at all, so the gap there is moot until one exists.
        PolicyView view = policyApi.getPolicy(policyNumber);
        enforceCustomerOwnPolicyOnly(view, jwt, authentication);
        enforceAgentOwnTeamOnly(view, jwt, authentication);
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
    /**
     * Finance's cash planning list: what matures in a window, and when (guide §6).
     *
     * <p>Declared before {@code /policies/{policyNumber}} for readability. Spring matches the
     * literal segment first either way, so a policy genuinely numbered "maturing" could not shadow
     * it -- but a reader should not have to know that to be sure.
     */
    @GetMapping("/policies/maturing")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<PolicySearchResponse> maturing(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok(PolicySearchResponse.from(policyApi.searchMaturing(from, to,
            PageRequest.of(page, Math.min(pageSize, 100), Sort.by("maturityDate", "policyNumber")))));
    }

    @GetMapping("/policies")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<PolicySearchResponse> searchPolicies(
            @RequestParam(required = false) UUID policyholderPartyId,
            @RequestParam(required = false) UUID relatedPartyId,
            @RequestParam(required = false) PolicyStatus status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        UUID effectivePolicyholderPartyId = isCustomer(authentication)
            ? ownPartyIdOrThrow(jwt) : policyholderPartyId;
        // A customer's relatedPartyId is DROPPED, not honoured and not rejected. It could not
        // widen anything -- it ANDs with the forced policyholder scope above -- but it could be
        // used as an oracle: "does policy X of mine name party Y as a beneficiary" answered one
        // guessed uuid at a time. A customer already sees their own policies in full, including
        // their beneficiaries, so the parameter buys them nothing and is simply not theirs.
        // Same fail-closed instinct as forcing the scope rather than validating it.
        UUID effectiveRelatedPartyId = isCustomer(authentication) ? null : relatedPartyId;
        // Agents are force-scoped to their own hierarchy team, the same "override the query, don't
        // merely check-then-reject" idiom as the customer scoping above -- an agents-realm token
        // cannot broaden this by supplying its own agentOfRecordId filter (there is none to
        // supply: this endpoint never accepted one), it can only ever see its own team's policies.
        // MUST be null (not Set.of()) for staff/customers: PolicyApiImpl.searchPolicies treats
        // null as "no agent filter" and a non-null EMPTY set as "filter to nothing" -- passing
        // Set.of() here for a non-agent caller would make every staff/customer search return zero
        // results.
        Set<UUID> agentOfRecordIds = isAgent(authentication) ? resolveOwnAgentTeamOrThrow(jwt) : null;
        // policyNumber breaks the tie, and it is load-bearing rather than tidy: `createdAt` alone
        // is not a total order, and two policies issued in the same instant -- which automatic
        // issuance off a batch of underwriting decisions produces routinely -- can then swap
        // places between page 1 and page 2, showing one twice and hiding the other entirely.
        Page<PolicyView> result = policyApi.searchPolicies(effectivePolicyholderPartyId, effectiveRelatedPartyId,
            status, agentOfRecordIds, q,
            PageRequest.of(page, Math.min(pageSize, 100),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("policyNumber"))));
        return ResponseEntity.ok(PolicySearchResponse.from(result));
    }

    // =================================================================================
    // Group business
    //
    // Its own /group-schemes resource rather than a branch inside /policies. A scheme
    // read as a policy answers "one contract, 500 lives, sum assured X" -- true, and
    // useless to somebody administering the schedule. The master policy stays readable at
    // GET /policies/{policyNumber} for everything a policy read is for.
    // =================================================================================

    /**
     * Issue a scheme with its opening schedule in one call.
     *
     * <p><b>UNDERWRITER, not plain staff.</b> This puts a contract on risk: it accepts N lives,
     * fixes the free cover limit and the premium, and the scheme is ACTIVE the moment it
     * returns. Every other action on this platform that decides whether risk is taken is
     * role-gated — an underwriting assessment and decision to UNDERWRITER, a claim assessment
     * to CLAIMS_ASSESSOR, a settlement to CLAIMS_MANAGER, an invoice waiver to FINANCE_OFFICER
     * — and this one was left at bare REALM_STAFF, so a claims assessor or a finance officer
     * could put a 500-life scheme on the books.
     *
     * <p>READS stay REALM_STAFF deliberately (see the two GETs below). A claims assessor has
     * to be able to read a schedule when a death is reported — that is exactly what
     * {@code idx_policy_member_party} was built for — but has no business admitting lives.
     *
     * <p>Still no agent- or customer-facing flow: a scheme is set up from a submitted employee
     * schedule at a desk, and opening one now because the endpoint exists would be a guess at
     * a screen nobody has drawn.
     */
    @PostMapping("/group-schemes")
    @PreAuthorize("hasRole('UNDERWRITER')")
    public ResponseEntity<GroupSchemeResponseDto> issueGroupScheme(
            @Valid @RequestBody IssueGroupSchemeRequestDto request, @AuthenticationPrincipal Jwt jwt) {
        // productId comes from the version, never from the caller: a request naming both
        // could name a version belonging to a different product, and the service would
        // then check the category of one and issue against the other.
        ProductSnapshotView snapshot = productApi.getSnapshotByVersionId(request.productVersionId());
        // AN EMPLOYER SCHEME NEEDS ITS UNDERWRITING CASE, exactly as manual issue does for a single
        // life. This route used to put a 500-life scheme on risk with no case at all. Credit life
        // is the exception by decision: set up by an underwriter from agreed terms, with their
        // name kept as its record (policy V24) -- recorded on every route regardless.
        if (snapshot.category() == ProductCategory.GROUP_LIFE && request.underwritingCaseId() == null) {
            throw new InvalidPolicyStateException("An employer scheme is issued on its underwriting case:"
                + " name the group case (underwritingCaseId) this scheme was proposed and assessed on");
        }
        GroupSchemeView view = policyApi.issueGroupScheme(request.toApiRequest(snapshot.productId()), jwt.getSubject(),
            request.underwritingCaseId(), TokenNames.displayName(jwt));
        return ResponseEntity.status(HttpStatus.CREATED).body(GroupSchemeResponseDto.from(view));
    }

    /**
     * Move the free cover limit on a live scheme.
     *
     * <p><b>The only term on a scheme that can be amended</b>, and the restriction is the domain's
     * rather than this endpoint's: an interest method and a repayment frequency were used to count
     * every existing member's schedule, and a premium rate was used to CHARGE them, so restating
     * any of those rewrites history. See {@code GroupScheme.amendFreeCoverLimit}.
     *
     * <p>UNDERWRITER, matching issuance: this decides how much of every borrower's loan is
     * insured, which is the same act as setting it in the first place.
     */
    @PutMapping("/group-schemes/{policyNumber}/free-cover-limit")
    @PreAuthorize("hasRole('UNDERWRITER')")
    public ResponseEntity<GroupSchemeResponseDto> amendFreeCoverLimit(
            @PathVariable String policyNumber,
            @Valid @RequestBody AmendFreeCoverLimitRequestDto request,
            @AuthenticationPrincipal Jwt jwt) {
        java.math.BigDecimal limit = request.fclAmount() == null || request.fclAmount().isBlank()
            ? null
            : new java.math.BigDecimal(request.fclAmount());
        return ResponseEntity.ok(GroupSchemeResponseDto.from(
            policyApi.amendFreeCoverLimit(policyNumber, limit, request.reason(), jwt.getSubject())));
    }

    @GetMapping("/group-schemes/{policyNumber}")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<GroupSchemeResponseDto> getGroupScheme(@PathVariable String policyNumber) {
        return ResponseEntity.ok(GroupSchemeResponseDto.from(policyApi.getGroupScheme(policyNumber)));
    }

    /**
     * One page of the member schedule.
     *
     * <p>The sort is fixed and TOTAL: {@code joinedOn} then the member id. A bulk schedule
     * gives every row the same join date, and paging a query whose order has ties can show
     * one member twice while never showing another -- on a member roll, a person who
     * believes they are insured and is missing from the page nobody scrolled twice.
     *
     * @param status omit for every member including those who have left
     * @param q omit for no name search. A 500-life schedule cannot be read by eye, so this
     *     is the only way to answer "is this person covered" without paging the whole roll.
     *     Matched against the party's display name, which lives in the party module — the
     *     member row itself holds only an id.
     */
    @GetMapping("/group-schemes/{policyNumber}/members")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<PolicyMemberResponseDto.PageResponse> listMembers(
            @PathVariable String policyNumber,
            @RequestParam(required = false) MemberStatus status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize) {
        Page<PolicyMemberView> result = policyApi.listMembers(policyNumber, status, q,
            PageRequest.of(page, Math.min(pageSize, 200),
                Sort.by(Sort.Direction.ASC, "joinedOn").and(Sort.by(Sort.Direction.ASC, "policyMemberId"))));
        return ResponseEntity.ok(PolicyMemberResponseDto.PageResponse.from(result));
    }

    /**
     * Correct who earns commission on a scheme, from now on. FINANCE_OFFICER or ADMIN, the same
     * as onboarding the agent and setting their rate: this decides where commission money goes.
     */
    @PostMapping("/group-schemes/{policyNumber}/agent-of-record")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<PolicyResponseDto> changeAgentOfRecord(@PathVariable String policyNumber,
            @Valid @RequestBody ChangeAgentOfRecordRequestDto request, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(PolicyResponseDto.from(policyApi.changeSchemeAgentOfRecord(
            policyNumber, request.agentOfRecordId(), request.reason(), jwt.getSubject())));
    }

    /**
     * One member, by id. REALM_STAFF, the same as the roll it is a row of: finance reads it to
     * say whose death a bank transfer settles, and holds only the member id the claim carries.
     */
    @GetMapping("/group-schemes/{policyNumber}/members/{policyMemberId}")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<PolicyMemberResponseDto> getMember(@PathVariable String policyNumber,
                                                             @PathVariable UUID policyMemberId) {
        return ResponseEntity.ok(PolicyMemberResponseDto.from(policyApi.getMember(policyNumber, policyMemberId)));
    }

    /**
     * Admit one life to an in-force scheme.
     *
     * <p>UNDERWRITER for the same reason issuing the scheme is: this accepts a new life onto a
     * live contract, values them against the scheme's basis, and tests them against its free
     * cover limit. It is the same decision as the opening schedule, taken one row at a time.
     */
    @PostMapping("/group-schemes/{policyNumber}/members")
    @PreAuthorize("hasRole('UNDERWRITER')")
    public ResponseEntity<PolicyMemberResponseDto> addMember(@PathVariable String policyNumber,
            @Valid @RequestBody GroupMemberInputDto request, @AuthenticationPrincipal Jwt jwt) {
        // A BORROWER JOINS BY FILE, NEVER ONE AT A TIME. A credit-life row admitted here skipped
        // everything the enrolment pipeline does -- the duplicate-loan and date checks, and above
        // all the second person who must accept every submission (credit-life design 2.10) -- on
        // one underwriter's word. The file path calls addMember itself, below this endpoint.
        if (policyApi.getGroupScheme(policyNumber).benefitBasis() == BenefitBasis.AMORTISING_LOAN) {
            throw new InvalidPolicyStateException(policyNumber + " is a credit-life scheme: borrowers join"
                + " through an enrolment file, which a second person accepts, not one at a time");
        }
        PolicyMemberView view = policyApi.addMember(policyNumber, request.toApiInput(), jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(PolicyMemberResponseDto.from(view));
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

    /**
     * Real controller mapping for a domain method (`PolicyApi.suspendPolicy`) that was fully
     * implemented, tested, and event-publishing since M3, but never reachable over HTTP by
     * anyone -- the M3 roadmap's own "Active -> Suspended -> Lapsed -> Reinstated/Surrendered"
     * acceptance criteria was met at the application/test layer and never carried through to the
     * API layer. `PolicyApiImpl.suspendPolicy` re-checks product-category eligibility
     * (POLICY_SUSPENSION_ELIGIBLE_CATEGORIES) and the ACTIVE-only guard; both surface as a real
     * 409 (INVALID_POLICY_STATE), already mapped by PolicyExceptionHandler.
     */
    @PostMapping("/policies/{policyNumber}/suspend")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<PolicyResponseDto> suspendPolicy(@PathVariable String policyNumber,
            @Valid @RequestBody SuspendPolicyRequestDto request, @AuthenticationPrincipal Jwt jwt) {
        policyApi.suspendPolicy(policyNumber, request.reason(), jwt.getSubject());
        return ResponseEntity.ok(PolicyResponseDto.from(policyApi.getPolicy(policyNumber)));
    }

    /** Same real-gap fix as suspendPolicy above -- SUSPENDED -> ACTIVE only, enforced by
     * `Policy.resume()`'s own guard (409 INVALID_POLICY_STATE on any other current status). */
    @PostMapping("/policies/{policyNumber}/resume")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<PolicyResponseDto> resumePolicy(@PathVariable String policyNumber, @AuthenticationPrincipal Jwt jwt) {
        policyApi.resumeSuspendedPolicy(policyNumber, jwt.getSubject());
        return ResponseEntity.ok(PolicyResponseDto.from(policyApi.getPolicy(policyNumber)));
    }

    /** Same real-gap fix as suspendPolicy above -- LAPSED -> REINSTATED only, gated by both
     * `Policy.reinstate()`'s guard and `PolicyApiImpl`'s own TZ_REINSTATEMENT_WINDOW_MONTHS
     * check (both 409 INVALID_POLICY_STATE). */
    @PostMapping("/policies/{policyNumber}/reinstate")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<PolicyResponseDto> reinstatePolicy(@PathVariable String policyNumber, @AuthenticationPrincipal Jwt jwt) {
        policyApi.reinstatePolicy(policyNumber, jwt.getSubject());
        return ResponseEntity.ok(PolicyResponseDto.from(policyApi.getPolicy(policyNumber)));
    }

    /** Make a savings policy paid-up: reduced cover, no further premium. 409 INVALID_POLICY_STATE
     * when the policy is not a savings product, not in a convertible status, or has no value yet. */
    @PostMapping("/policies/{policyNumber}/paid-up")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<PolicyResponseDto> makePaidUp(@PathVariable String policyNumber, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(PolicyResponseDto.from(policyApi.makePaidUp(policyNumber, jwt.getSubject())));
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
        body.put("bonusSurrenderValue", Map.of("amount", quote.bonusSurrenderValueAmount().toPlainString(),
            "currencyCode", quote.quotedValueCurrency()));
        return ResponseEntity.ok(body);
    }

    /**
     * Request a customer surrender (step 1, task 4). Was a deferred 501; the payout now runs through
     * the disbursement rail, event-driven, so no workflow engine is needed. Staff-only and split into
     * request/approve, because money leaves the company and the two must be different people. 409 on
     * an ineligible policy, 404 if it does not exist.
     */
    @PostMapping("/policies/{policyNumber}/surrender")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<SurrenderRequestResponseDto> requestSurrender(@PathVariable String policyNumber,
            @Valid @RequestBody SurrenderRequestDto request, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(SurrenderRequestResponseDto.from(
            policyApi.requestSurrender(policyNumber, request.payeeRef(), jwt.getSubject())));
    }

    /** The policy's latest surrender request, or 204 when it has none. Read by the policy page so the
     *  approver can find a request to approve -- there was no other way to reach one. */
    @GetMapping("/policies/{policyNumber}/surrender-request")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<SurrenderRequestResponseDto> latestSurrenderRequest(@PathVariable String policyNumber) {
        return policyApi.findLatestSurrenderRequest(policyNumber)
            .map(SurrenderRequestResponseDto::from)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.noContent().build());
    }

    /** Approve a surrender, by someone other than the requester. Cover stops and the payout is sent.
     *  Finance-only, like a commission payout: approving is what moves money out of the company. */
    @PostMapping("/surrender-requests/{surrenderRequestId}/approve")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<SurrenderRequestResponseDto> approveSurrender(@PathVariable UUID surrenderRequestId,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(SurrenderRequestResponseDto.from(
            policyApi.approveSurrender(surrenderRequestId, jwt.getSubject())));
    }

    /** The process-status poll from the old deferred-choreography design. Kept as a 501: there is
     * no long-running process now -- surrender completes through events -- so there is nothing to
     * poll, but the routable endpoint stays rather than 404. */
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

    /**
     * A funeral policy's covered lives, main member first (family funeral cover); an empty list for any
     * other policy. Scoped exactly as getPolicy: a customer to their own policy, an agent to their team's.
     */
    @GetMapping("/policies/{policyNumber}/covered-lives")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<List<CoveredLifeView>> getCoveredLives(@PathVariable String policyNumber,
            @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        PolicyView view = policyApi.getPolicy(policyNumber);
        enforceCustomerOwnPolicyOnly(view, jwt, authentication);
        enforceAgentOwnTeamOnly(view, jwt, authentication);
        return ResponseEntity.ok(policyApi.coveredLives(policyNumber));
    }

    /**
     * Add a life to an in-force funeral policy, covered from the next premium date. Staff only: it changes
     * what the family pays, so it is an act on the contract, not a self-service edit.
     */
    @PostMapping("/policies/{policyNumber}/covered-lives")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<CoveredLifeView> addCoveredLife(@PathVariable String policyNumber,
            @RequestBody CoveredLifeRequests.Add request, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(policyApi.addCoveredLife(policyNumber, request.toLife(), jwt.getSubject()));
    }

    /**
     * Promote a name-only covered life to a registered party at claim, from its identity document (plan
     * R10). Claims staff do this when a death certificate arrives; idempotent.
     */
    @PostMapping("/policies/{policyNumber}/covered-lives/{coveredLifeId}/promotion")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('CLAIMS_ASSESSOR') or hasRole('CLAIMS_MANAGER'))")
    public ResponseEntity<CoveredLifeView> promoteCoveredLife(@PathVariable String policyNumber, @PathVariable UUID coveredLifeId,
            @RequestBody CoveredLifeRequests.Identify request, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(policyApi.promoteCoveredLife(policyNumber, coveredLifeId, request.toRequest(), jwt.getSubject()));
    }

    /**
     * The spouse takes over a funeral policy the main member's death left waiting (plan R8): registered from
     * their identity document, made policyholder, re-priced as the main member from the next premium date.
     */
    @PostMapping("/policies/{policyNumber}/takeover")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<PolicyResponseDto> takeOverFuneralPolicy(@PathVariable String policyNumber,
            @RequestBody CoveredLifeRequests.Identify request, @AuthenticationPrincipal Jwt jwt) {
        policyApi.takeOverFuneralPolicy(policyNumber, request.toRequest(), jwt.getSubject());
        return ResponseEntity.ok(PolicyResponseDto.from(policyApi.getPolicy(policyNumber)));
    }

    /** Take a dependant off cover at the next premium date. Staff only, as adding is. */
    @PostMapping("/policies/{policyNumber}/covered-lives/{coveredLifeId}/removal")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<CoveredLifeView> removeCoveredLife(@PathVariable String policyNumber, @PathVariable UUID coveredLifeId,
            @RequestBody(required = false) CoveredLifeRequests.Remove request, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(policyApi.removeCoveredLife(policyNumber, coveredLifeId,
            request != null ? request.reason() : null, jwt.getSubject()));
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

    static boolean isAgent(Authentication authentication) {
        return authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority).anyMatch("ROLE_REALM_AGENTS"::equals);
    }

    /** Resolves the caller's own {@code DistributionApi.resolveAgentTeam} result (itself plus its
     *  hierarchy downline), 403ing if the token carries no {@code party_id} claim at all -- same
     *  shape as {@link #ownPartyIdOrThrow}. An EMPTY (but non-null) team is a legitimate result of
     *  {@code resolveAgentTeam} itself (the party is not an agent in this tenant) and is left to
     *  the caller to interpret -- {@link #searchPolicies} treats it as "no policies", not an error,
     *  since a search naturally degrades to nothing rather than needing a special-cased 403. */
    private Set<UUID> resolveOwnAgentTeamOrThrow(Jwt jwt) {
        String ownPartyId = jwt.getClaimAsString("party_id");
        if (ownPartyId == null) {
            throw new AccessDeniedException("Agent token carries no party_id claim");
        }
        return Set.copyOf(distributionApi.resolveAgentTeam(UUID.fromString(ownPartyId)));
    }

    /**
     * Object-level authorization for agents, the mirror of {@link #enforceCustomerOwnPolicyOnly}:
     * an agents-realm token may only read a policy whose {@code agentOfRecordId} is itself or
     * someone in its own hierarchy downline. A policy with NO agent of record (sold direct) is
     * therefore never visible to an agents-realm caller -- there is no "unattributed" bucket an
     * agent is entitled to browse. Unlike the customer check (a single ownPartyId comparison), this
     * genuinely needs a real lookup ({@code DistributionApi.resolveAgentTeam}), which is exactly
     * the capability this endpoint's own comment used to name as the missing piece.
     */
    private void enforceAgentOwnTeamOnly(PolicyView view, Jwt jwt, Authentication authentication) {
        if (!isAgent(authentication)) {
            return;
        }
        if (view.agentOfRecordId() == null || !resolveOwnAgentTeamOrThrow(jwt).contains(view.agentOfRecordId())) {
            throw new AccessDeniedException("Access denied: agent may only access policies in their own book of business");
        }
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
