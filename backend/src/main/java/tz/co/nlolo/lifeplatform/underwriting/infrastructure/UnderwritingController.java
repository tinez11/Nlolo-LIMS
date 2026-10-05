package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.underwriting.api.AnnuityChoice;
import tz.co.nlolo.lifeplatform.underwriting.api.DeferredAnnuityChoice;
import tz.co.nlolo.lifeplatform.underwriting.api.FuneralApplication;
import tz.co.nlolo.lifeplatform.underwriting.api.UnitLinkedChoice;
import tz.co.nlolo.lifeplatform.underwriting.api.BeneficiaryNomination;
import tz.co.nlolo.lifeplatform.underwriting.api.GroupProposal;
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

    // Transactional so an annuity choice refused below rolls back the case it would have opened:
    // a 422 must not leave a case behind (product step 5). Both service calls join this transaction.
    @PostMapping("/cases")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<UnderwritingCaseView> openCase(@Valid @RequestBody OpenCaseRequest request,
                                                          @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                          @AuthenticationPrincipal Jwt jwt) {
        // Idempotency-Key accepted but not yet enforced -- see plan Global Constraints;
        // real dedup registry lands with payment's idempotency work in M5.
        UnderwritingCaseView view = underwritingApi.openCase(request.applicantPartyId(), request.productId(), request.productVersionId(),
            new BigDecimal(request.sumAssured().amount()), request.sumAssured().currencyCode(),
            request.agentOfRecordId(),
            new ProposalDetails(request.lifeAssuredPartyId(), request.branch(),
                request.sourceOfBusiness(), request.proposedCommencementDate(),
                request.requestedTermMonths(), request.premiumPayingTermMonths(),
                request.premiumFrequency(),
                request.beneficiaries() == null ? List.of()
                    : request.beneficiaries().stream().map(OpenCaseRequest.BeneficiaryNominationDto::toApiNomination).toList()),
            jwt.getSubject());
        // Recorded with the case when the applicant chose up front (product step 5). In the same
        // request, so a refused choice is a 422 on the request that carried it.
        if (request.annuityChoice() != null) {
            underwritingApi.recordAnnuityChoice(view.caseId(), request.annuityChoice().toApi(), jwt.getSubject());
        }
        if (request.deferredAnnuity() != null) {
            underwritingApi.recordDeferredAnnuityChoice(view.caseId(), request.deferredAnnuity().age(), jwt.getSubject());
        }
        if (request.funeral() != null) {
            underwritingApi.recordFuneralApplication(view.caseId(), request.funeral().planCode(), request.funeral().lives(),
                jwt.getSubject());
        }
        if (request.unitLinked() != null) {
            underwritingApi.recordUnitLinkedChoice(view.caseId(), request.unitLinked(), jwt.getSubject());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    /**
     * Propose a group scheme.
     *
     * <p>UNDERWRITER, matching {@code POST /group-schemes}: proposing a scheme is the front of
     * the same act that ends in a contract on risk. Its own path rather than a branch inside
     * {@code POST /cases} because {@link OpenCaseRequest} requires a sum assured and a group
     * case deliberately has none — see {@link OpenGroupCaseRequest}.
     */
    @PostMapping("/cases/group")
    @PreAuthorize("hasRole('UNDERWRITER')")
    public ResponseEntity<UnderwritingCaseView> openGroupCase(@Valid @RequestBody OpenGroupCaseRequest request,
                                                               @AuthenticationPrincipal Jwt jwt) {
        UnderwritingCaseView view = underwritingApi.openCase(request.policyholderPartyId(),
            request.productId(), request.productVersionId(), request.agentOfRecordId(),
            request.toApiProposal(), jwt.getSubject());
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
            new UnderwritingApi.DecisionInput(request.outcome(), request.loadingPercent(), request.reason(),
                Boolean.TRUE.equals(request.ageEvidenceConfirmed())),
            jwt.getSubject(), senior);
        return ResponseEntity.ok(view);
    }

    /**
     * An annuity case's choice (product step 5). 404 ANNUITY_CHOICE_NOT_FOUND when there is none --
     * which is also the answer for every case that is not an annuity's.
     */
    @GetMapping("/cases/{caseId}/annuity-choice")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<AnnuityChoice> getAnnuityChoice(@PathVariable UUID caseId) {
        return underwritingApi.annuityChoice(caseId).map(ResponseEntity::ok)
            .orElseThrow(() -> new AnnuityChoiceNotFoundException(caseId));
    }

    /** Change an annuity case's choice before it is decided. Whoever may open a case may change it. */
    @PutMapping("/cases/{caseId}/annuity-choice")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<AnnuityChoice> recordAnnuityChoice(@PathVariable UUID caseId,
                                                             @RequestBody OpenCaseRequest.AnnuityChoiceDto request,
                                                             @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(underwritingApi.recordAnnuityChoice(caseId, request.toApi(), jwt.getSubject()));
    }

    /**
     * A deferred annuity case's retirement age and target date (product step 5 D2). 404
     * DEFERRED_ANNUITY_CHOICE_NOT_FOUND when there is none -- also the answer for every other case.
     */
    @GetMapping("/cases/{caseId}/deferred-annuity-choice")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<DeferredAnnuityChoice> getDeferredAnnuityChoice(@PathVariable UUID caseId) {
        return underwritingApi.deferredAnnuityChoice(caseId).map(ResponseEntity::ok)
            .orElseThrow(() -> new DeferredAnnuityChoiceNotFoundException(caseId));
    }

    /** Change a deferred annuity case's retirement age before it is decided. Whoever may open a case may change it. */
    @PutMapping("/cases/{caseId}/deferred-annuity-choice")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<DeferredAnnuityChoice> recordDeferredAnnuityChoice(@PathVariable UUID caseId,
                                                                             @RequestBody OpenCaseRequest.DeferredAnnuityDto request,
                                                                             @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(underwritingApi.recordDeferredAnnuityChoice(caseId, request.age(), jwt.getSubject()));
    }

    /**
     * A unit-linked case's fund split, premium and sum assured (product step 6). 404 UNIT_LINKED_CHOICE_NOT_FOUND
     * when there is none -- also the answer for every other case.
     */
    @GetMapping("/cases/{caseId}/unit-linked-choice")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<UnitLinkedChoice> getUnitLinkedChoice(@PathVariable UUID caseId) {
        return underwritingApi.unitLinkedChoice(caseId).map(ResponseEntity::ok)
            .orElseThrow(() -> new UnitLinkedChoiceNotFoundException(caseId));
    }

    /** Record or replace a unit-linked case's choice before it is decided. Whoever may open a case may change it. */
    @PutMapping("/cases/{caseId}/unit-linked-choice")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<UnitLinkedChoice> recordUnitLinkedChoice(@PathVariable UUID caseId,
                                                                   @RequestBody UnitLinkedChoice request,
                                                                   @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(underwritingApi.recordUnitLinkedChoice(caseId, request, jwt.getSubject()));
    }

    /**
     * A funeral case's plan, dependants and a fresh quote of the family (family funeral cover). 404
     * FUNERAL_APPLICATION_NOT_FOUND when there is none -- also the answer for every other case.
     */
    @GetMapping("/cases/{caseId}/funeral-application")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<FuneralApplication> getFuneralApplication(@PathVariable UUID caseId) {
        return underwritingApi.funeralApplication(caseId).map(ResponseEntity::ok)
            .orElseThrow(() -> new FuneralApplicationNotFoundException(caseId));
    }

    /** Record or replace a funeral case's plan and dependants before it is decided. Whoever may open a case may change it. */
    @PutMapping("/cases/{caseId}/funeral-application")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<FuneralApplication> recordFuneralApplication(@PathVariable UUID caseId,
                                                                       @Valid @RequestBody FuneralApplicationRequest request,
                                                                       @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(underwritingApi.recordFuneralApplication(caseId, request.planCode(), request.lives(),
            jwt.getSubject()));
    }

    /**
     * The beneficiary nominations taken on a proposal.
     *
     * <p>Its own sub-resource rather than a field on {@code UnderwritingCaseView}, matching how
     * disclosures are already exposed on this module. The case view is what
     * {@code GET /underwriting/cases} returns twenty of, and fetching nominations for each row
     * would be twenty queries for something the queue never displays — so a list view carries
     * none, and an empty array there would read as "nobody nominated" when it means "not
     * loaded". A separate resource cannot be misread that way.
     *
     * <p>The console needs this before manual-issuing against a case: that form sends its own
     * beneficiary list, so without prefilling from here a proposal's nominations would be
     * silently dropped by the very path meant to honour them.
     *
     * <p>Same gate as {@link #getCase}: a caller who may read the case may read who it names.
     */
    @GetMapping("/cases/{caseId}/beneficiaries")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<List<BeneficiaryNomination>> listCaseBeneficiaries(@PathVariable UUID caseId) {
        return ResponseEntity.ok(underwritingApi.getCase(caseId).beneficiaries());
    }

    /**
     * The group scheme this case proposes: its terms, grade table and opening schedule.
     *
     * <p>Its own sub-resource rather than a field on the case, for the same reason
     * {@link #listCaseBeneficiaries} is one — and more so. A scheme's schedule runs to
     * hundreds of lives, and {@code listCases} returns the same {@code UnderwritingCaseView}
     * this endpoint's parent does, so putting the proposal on the view would ship a 500-row
     * schedule with every row of a twenty-case queue page. The case itself carries only the
     * {@code groupScheme} flag, which is what a queue needs.
     *
     * <p>Same gate as {@link #getCase}: a caller who may read the case may read what it asks
     * for.
     *
     * @return 404 when the case is not a scheme — there is no proposal to fetch, and that is
     *     the plain meaning of the URL not resolving to anything.
     */
    @GetMapping("/cases/{caseId}/group-proposal")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<GroupProposalResponseDto> getGroupProposal(@PathVariable UUID caseId) {
        GroupProposal proposal = underwritingApi.getCase(caseId).groupProposal();
        return proposal != null
            ? ResponseEntity.ok(GroupProposalResponseDto.from(proposal))
            : ResponseEntity.notFound().build();
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
