package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.underwriting.api.MedicalDisclosureView;
import tz.co.nlolo.lifeplatform.underwriting.api.ProposalDetails;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseStatus;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseView;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/underwriting")
public class UnderwritingController {

    private final UnderwritingApi underwritingApi;
    /** Resolves an agents-realm caller's own registered clients, to scope the case list by. */
    private final PartyApi partyApi;

    public UnderwritingController(UnderwritingApi underwritingApi, PartyApi partyApi) {
        this.underwritingApi = underwritingApi;
        this.partyApi = partyApi;
    }

    /**
     * <b>This endpoint had no agent scoping at all.</b> It has been readable by
     * {@code REALM_AGENTS} since M4 while taking no JWT and applying no filter, so any
     * agents-realm token could list every underwriting case in the tenant — including applicants
     * who are not its clients, with their sum assured and decision. Policies and claims both
     * force-scope agents; this one was simply missed, and adding an {@code applicantPartyId} filter
     * for the client register would have handed that hole a precise aim.
     *
     * <p>Now scoped with the same "override the query, don't check-then-reject" idiom
     * {@code PolicyController.searchPolicies} and {@code ClaimController.listClaims} use: an
     * agents-realm caller cannot broaden the result by supplying its own {@code applicantPartyId},
     * because its scope set is applied on top of whatever it asked for.
     *
     * <p>The scope is parties the agent REGISTERED, matching {@code GET /parties} and the
     * party-detail read, so "my clients" means one thing across all three. An agent who has
     * registered nobody gets an empty set and therefore zero rows — not, as a null would mean,
     * every case in the tenant.
     */
    @GetMapping("/cases")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<UnderwritingCaseSearchResponse> listCases(
            @RequestParam(required = false) UnderwritingCaseStatus status,
            @RequestParam(required = false) UUID applicantPartyId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        boolean isAgent = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority).anyMatch("ROLE_REALM_AGENTS"::equals);
        // MUST stay null for staff: null means "no scope", an empty set means "scope to nothing".
        Set<UUID> applicantPartyIds = isAgent ? partyApi.partyIdsRegisteredBy(jwt.getSubject()) : null;

        Pageable pageable = PageRequest.of(page, Math.min(pageSize, 100), Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<UnderwritingCaseView> result = underwritingApi.listCases(status, applicantPartyId, applicantPartyIds, pageable);
        return ResponseEntity.ok(UnderwritingCaseSearchResponse.from(result));
    }

    @PostMapping("/cases")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<UnderwritingCaseView> openCase(@Valid @RequestBody OpenCaseRequest request,
                                                          @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                          @AuthenticationPrincipal Jwt jwt) {
        // Idempotency-Key accepted but not yet enforced -- see plan Global Constraints;
        // real dedup registry lands with payment's idempotency work in M5.
        UnderwritingCaseView view = underwritingApi.openCase(request.applicantPartyId(), request.productId(), request.productVersionId(),
            new BigDecimal(request.sumAssured().amount()), request.sumAssured().currencyCode(),
            request.agentOfRecordId(),
            new ProposalDetails(request.lifeAssuredPartyId(), request.branch(),
                request.sourceOfBusiness(), request.proposedCommencementDate()),
            jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @GetMapping("/cases/{caseId}")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<UnderwritingCaseView> getCase(@PathVariable UUID caseId) {
        return ResponseEntity.ok(underwritingApi.getCase(caseId));
    }

    @PostMapping("/cases/{caseId}/assessments")
    @PreAuthorize("hasRole('UNDERWRITER')")
    public ResponseEntity<UnderwritingCaseView> submitAssessment(@PathVariable UUID caseId,
                                                                  @Valid @RequestBody SubmitAssessmentRequest request,
                                                                  @AuthenticationPrincipal Jwt jwt) {
        UnderwritingCaseView view = underwritingApi.submitAssessment(caseId, request.assessmentType(), request.findings(), request.riskScore(), jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    /**
     * The underwriting decision -- the only thing that settles a case and therefore the only
     * thing that puts a policy in force.
     *
     * <p>{@code UNDERWRITER} to decide at all. Departing from the engine's recommendation
     * additionally requires {@code SENIOR_UNDERWRITER}, and that half is enforced in the
     * service rather than here: whether a decision IS an override depends on the case's
     * current recommendation, which no {@code @PreAuthorize} expression can see. The role is
     * read off the token and passed down, so the underwriting module keeps no Spring Security
     * dependency of its own.
     */
    @PostMapping("/cases/{caseId}/decision")
    @PreAuthorize("hasRole('UNDERWRITER')")
    public ResponseEntity<UnderwritingCaseView> decide(@PathVariable UUID caseId,
                                                        @Valid @RequestBody DecideRequest request,
                                                        @AuthenticationPrincipal Jwt jwt,
                                                        Authentication authentication) {
        boolean senior = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch("ROLE_SENIOR_UNDERWRITER"::equals);
        UnderwritingCaseView view = underwritingApi.decide(caseId,
            new UnderwritingApi.DecisionInput(request.outcome(), request.loadingPercent(), request.reason()),
            jwt.getSubject(), senior);
        return ResponseEntity.ok(view);
    }

    @PostMapping("/cases/{caseId}/referral")
    @PreAuthorize("hasRole('UNDERWRITER')")
    public ResponseEntity<Void> referCase(@PathVariable UUID caseId) {
        underwritingApi.referToSeniorUnderwriter(caseId);
        return ResponseEntity.ok().build();
    }

    /**
     * Records what an applicant declared on a proposal form.
     *
     * <p>REALM_AGENTS or REALM_STAFF, deliberately NOT the UNDERWRITER role that gates assessment
     * and referral. Taking a proposal is not underwriting it -- the agent sitting with the
     * customer is the person who asks the questions and writes down the answers, and requiring an
     * underwriter would put the record in the hands of someone who was not in the room.
     *
     * <p>The {@code medical_disclosure} table has existed since M4 with no writer at all, while
     * claims computes and displays {@code requiresContestabilityReview} -- a review with nothing
     * to review. This is the writer.
     */
    @PostMapping("/cases/{caseId}/disclosures")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<MedicalDisclosureView> recordDisclosures(@PathVariable UUID caseId,
            @Valid @RequestBody RecordDisclosuresRequest request, @AuthenticationPrincipal Jwt jwt) {
        MedicalDisclosureView view = underwritingApi.recordDisclosures(caseId,
            request.toApiAnswers(), jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @GetMapping("/cases/{caseId}/disclosures")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<List<MedicalDisclosureView>> listDisclosures(@PathVariable UUID caseId) {
        return ResponseEntity.ok(underwritingApi.listDisclosures(caseId));
    }
}
