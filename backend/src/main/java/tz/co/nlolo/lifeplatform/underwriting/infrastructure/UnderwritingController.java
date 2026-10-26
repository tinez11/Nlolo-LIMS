package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import tz.co.nlolo.lifeplatform.iam.api.CurrentAccess;
import tz.co.nlolo.lifeplatform.iam.api.Permission;
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
import tz.co.nlolo.lifeplatform.underwriting.api.CaseFilter;
import tz.co.nlolo.lifeplatform.underwriting.api.DecisionOutcome;
import tz.co.nlolo.lifeplatform.underwriting.api.ReferralStatus;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
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
import tz.co.nlolo.lifeplatform.underwriting.api.ProposalAmendmentApi;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/underwriting")
public class UnderwritingController {

    /** Decision dates are civil days here, never UTC: a case decided at 01:00 EAT was decided that day, not the day before. */
    private static final ZoneId CIVIL = ZoneId.of("Africa/Dar_es_Salaam");

    private final UnderwritingApi underwritingApi;
    /** Resolves an agents-realm caller's own registered clients, to scope the case list by. */
    private final PartyApi partyApi;

    /** The quotation a proposal was opened from (2026-10-20). */
    private final tz.co.nlolo.lifeplatform.underwriting.api.AcceptedQuotationApi acceptedQuotations;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /** Returned for amendment, amended, resubmitted (2026-10-20). */
    private final ProposalAmendmentApi amendments;
    /** Whether the caller may decide against the recommendation -- a platform permission, read per request. */
    private final CurrentAccess currentAccess;

    public UnderwritingController(UnderwritingApi underwritingApi, PartyApi partyApi,
                                  tz.co.nlolo.lifeplatform.underwriting.api.AcceptedQuotationApi acceptedQuotations,
                                  com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                                  ProposalAmendmentApi amendments, CurrentAccess currentAccess) {
        this.currentAccess = currentAccess;
        this.underwritingApi = underwritingApi;
        this.partyApi = partyApi;
        this.acceptedQuotations = acceptedQuotations;
        this.objectMapper = objectMapper;
        this.amendments = amendments;
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
    @PreAuthorize("hasRole('REALM_AGENTS') or @access.can('underwriting.view')")
    public ResponseEntity<UnderwritingCaseSearchResponse> listCases(
            @RequestParam(required = false) UnderwritingCaseStatus status,
            @RequestParam(required = false) DecisionOutcome outcome,
            @RequestParam(required = false) ReferralStatus referral,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate decidedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate decidedTo,
            @RequestParam(required = false) UUID applicantPartyId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        boolean isAgent = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority).anyMatch("ROLE_REALM_AGENTS"::equals);
        // MUST stay null for staff: null means "no scope", an empty set means "scope to nothing".
        Set<UUID> applicantPartyIds = isAgent ? partyApi.partyIdsRegisteredBy(jwt.getSubject()) : null;

        // Civil days in Africa/Dar_es_Salaam, half-open: decidedFrom 00:00 inclusive to decidedTo+1 00:00 exclusive,
        // so "decided in October" is every instant of the 1st through the 31st and nothing of 1 November.
        Instant from = decidedFrom == null ? null : decidedFrom.atStartOfDay(CIVIL).toInstant();
        Instant to = decidedTo == null ? null : decidedTo.plusDays(1).atStartOfDay(CIVIL).toInstant();
        CaseFilter filter = CaseFilter.status(status).withOutcome(outcome).withReferral(referral)
            .withDecided(from, to).withApplicant(applicantPartyId).withApplicants(applicantPartyIds);

        Pageable pageable = PageRequest.of(page, Math.min(pageSize, 100), Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<UnderwritingCaseView> result = underwritingApi.listCases(filter, pageable);
        return ResponseEntity.ok(UnderwritingCaseSearchResponse.from(result));
    }

    /**
     * Decided cases no policy has been issued from (2026-10-08) -- the manual-issue screen's list. The decided
     * set grows with every case ever decided; nearly all of them already have their policy.
     */
    @GetMapping("/cases/awaiting-issue")
    @PreAuthorize("@access.canAny('underwriting.view', 'policies.issue')")
    public ResponseEntity<UnderwritingCaseSearchResponse> listCasesAwaitingIssue(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        Pageable pageable = PageRequest.of(page, Math.min(pageSize, 100), Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(UnderwritingCaseSearchResponse.from(underwritingApi.listCasesAwaitingIssue(q, pageable)));
    }

    // Transactional so an annuity choice refused below rolls back the case it would have opened:
    // a 422 must not leave a case behind (product step 5). Both service calls join this transaction.
    @PostMapping("/cases")
    @PreAuthorize("hasRole('REALM_AGENTS') or @access.can('underwriting.propose')")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<UnderwritingCaseView> openCase(@Valid @RequestBody OpenCaseRequest request,
                                                          @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                          @AuthenticationPrincipal Jwt jwt) {
        // An agent proposes only for a client it registered (2026-10-20): any other applicant is the 404 the party read
        // gives an agent for someone not theirs.
        if (jwt != null && org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication()
                .getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch("ROLE_REALM_AGENTS"::equals)
                && !partyApi.partyIdsRegisteredBy(jwt.getSubject()).contains(request.applicantPartyId())) {
            throw new tz.co.nlolo.lifeplatform.party.api.PartyNotFoundException(request.applicantPartyId());
        }
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
                    : request.beneficiaries().stream().map(OpenCaseRequest.BeneficiaryNominationDto::toApiNomination).toList(),
                request.proposedRatePercent()),
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
        if (request.accountChargeIds() != null && !request.accountChargeIds().isEmpty()) {
            underwritingApi.chooseAccountCharges(view.caseId(), request.accountChargeIds());
        }
        view = applySale(view, request.salesChannel(), request.branchCode(), jwt);
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    /**
     * The sale as the request names it, else as the case defaulted it, with the opening staff member's home branch
     * (the {@code home_branch} token claim, IFRS 17 I2) where neither the request nor an agent gave one. Nothing is
     * written when nothing changes, and nothing when no branch is known at all -- the case then waits for one.
     */
    private UnderwritingCaseView applySale(UnderwritingCaseView view, String requestedChannel, String requestedBranch,
                                           Jwt jwt) {
        String channel = requestedChannel != null ? requestedChannel : view.salesChannel();
        String branch = requestedBranch != null ? requestedBranch
            : view.branchCode() != null ? view.branchCode() : jwt.getClaimAsString("home_branch");
        if (branch == null || (java.util.Objects.equals(channel, view.salesChannel())
                && java.util.Objects.equals(branch, view.branchCode()))) {
            return view;
        }
        return underwritingApi.recordSale(view.caseId(), channel, branch, jwt.getSubject());
    }

    /** IFRS 17 I2: the channel and branch of the sale, editable until the policy is issued. */
    @PutMapping("/cases/{caseId}/sale")
    @PreAuthorize("hasRole('REALM_AGENTS') or @access.can('underwriting.propose')")
    public ResponseEntity<UnderwritingCaseView> recordSale(@PathVariable UUID caseId, @Valid @RequestBody SaleRequest request,
                                                            @AuthenticationPrincipal Jwt jwt) {
        requireAgentScope(caseId);
        return ResponseEntity.ok(underwritingApi.recordSale(caseId, request.salesChannel(), request.branchCode(),
            jwt.getSubject()));
    }

    /** The account charges a savings case will be issued on (2026-10-09); empty for the product's own. */
    @GetMapping("/cases/{caseId}/account-charges")
    @PreAuthorize("hasRole('REALM_AGENTS') or @access.can('underwriting.view')")
    public ResponseEntity<AccountChargeChoice> accountCharges(@PathVariable UUID caseId) {
        requireAgentScope(caseId);
        return ResponseEntity.ok(new AccountChargeChoice(underwritingApi.accountCharges(caseId)));
    }

    /** Choose or change them (empty clears) -- until the policy is issued. */
    @PutMapping("/cases/{caseId}/account-charges")
    @PreAuthorize("@access.can('underwriting.propose')")
    public ResponseEntity<AccountChargeChoice> chooseAccountCharges(@PathVariable UUID caseId,
                                                                    @RequestBody AccountChargeChoice request) {
        underwritingApi.chooseAccountCharges(caseId, request.chargeIds());
        return ResponseEntity.ok(new AccountChargeChoice(underwritingApi.accountCharges(caseId)));
    }

    /** {@code /underwriting/cases/{caseId}/account-charges}. */
    public record AccountChargeChoice(List<UUID> chargeIds) {}

    /** {@code PUT /underwriting/cases/{caseId}/sale}. */
    public record SaleRequest(@jakarta.validation.constraints.NotBlank String salesChannel,
                              @jakarta.validation.constraints.NotBlank String branchCode) {}

    /**
     * Propose a group scheme.
     *
     * <p>UNDERWRITER, matching {@code POST /group-schemes}: proposing a scheme is the front of
     * the same act that ends in a contract on risk. Its own path rather than a branch inside
     * {@code POST /cases} because {@link OpenCaseRequest} requires a sum assured and a group
     * case deliberately has none — see {@link OpenGroupCaseRequest}.
     */
    @PostMapping("/cases/group")
    @PreAuthorize("@access.can('schemes.manage')")
    public ResponseEntity<UnderwritingCaseView> openGroupCase(@Valid @RequestBody OpenGroupCaseRequest request,
                                                               @AuthenticationPrincipal Jwt jwt) {
        UnderwritingCaseView view = underwritingApi.openCase(request.policyholderPartyId(),
            request.productId(), request.productVersionId(), request.agentOfRecordId(),
            request.toApiProposal(), jwt.getSubject());
        view = applySale(view, request.salesChannel(), request.branchCode(), jwt);
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    /**
     * Group funeral schemes (2026-10-07): replace an undecided proposal's members and families from a CSV file. 200
     * either way -- {@code accepted} says whether the schedule changed, {@code problems} every row or member at fault.
     */
    @PostMapping(value = "/cases/{caseId}/group-schedule",
        consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@access.can('schemes.manage')")
    public ResponseEntity<tz.co.nlolo.lifeplatform.underwriting.api.GroupScheduleResult> replaceGroupSchedule(
            @PathVariable UUID caseId,
            @org.springframework.web.bind.annotation.RequestPart("file") org.springframework.web.multipart.MultipartFile file,
            @AuthenticationPrincipal Jwt jwt) {
        try {
            return ResponseEntity.ok(underwritingApi.replaceGroupSchedule(caseId, file.getBytes(), jwt.getSubject()));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Failed to read the uploaded schedule", e);
        }
    }

    /** The lives an association's file names and every row's problem, read by the server's own parser. */
    public record GroupScheduleReading(List<tz.co.nlolo.lifeplatform.underwriting.api.GroupProposal.LifeLine> lives,
                                       List<String> problems) {}

    /**
     * Reads a group funeral schedule file without storing anything (2026-10-07), so a proposal being written can take
     * its families from the association's file: the console fills its form from the lives and shows the problems.
     * The plan's role rules are applied when the proposal is opened.
     */
    @PostMapping(value = "/group-schedule/reading", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@access.can('schemes.manage')")
    public ResponseEntity<GroupScheduleReading> readGroupSchedule(
            @org.springframework.web.bind.annotation.RequestPart("file") org.springframework.web.multipart.MultipartFile file) {
        try {
            var parsed = tz.co.nlolo.lifeplatform.underwriting.api.FuneralScheduleFile.parse(file.getBytes());
            return ResponseEntity.ok(new GroupScheduleReading(parsed.lives(), parsed.errors()));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Failed to read the uploaded schedule", e);
        }
    }

    /** The schedule file's header and one example family, for an association to fill. */
    @GetMapping(value = "/group-schedule-template", produces = "text/csv")
    @PreAuthorize("@access.can('underwriting.view')")
    public ResponseEntity<String> groupScheduleTemplate() {
        String csv = String.join(",", tz.co.nlolo.lifeplatform.underwriting.api.FuneralScheduleFile.HEADER) + "\n"
            + "M001,MAIN_MEMBER,Juma Ali,1980-05-12,MALE,,,Asha Juma,SPOUSE,0712000000\n"
            + "M001,SPOUSE,Asha Juma,1983-02-01,FEMALE,,,,,\n"
            + "M001,CHILD,Neema Juma,2012-07-20,FEMALE,,,,,\n";
        return ResponseEntity.ok()
            .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"group-funeral-schedule-template.csv\"")
            .contentType(org.springframework.http.MediaType.parseMediaType("text/csv"))
            .body(csv);
    }

    @GetMapping("/cases/{caseId}")
    @PreAuthorize("hasRole('REALM_AGENTS') or @access.can('underwriting.view')")
    public ResponseEntity<UnderwritingCaseView> getCase(@PathVariable UUID caseId) {
        return ResponseEntity.ok(requireAgentScope(caseId));
    }

    /**
     * The quotation the customer agreed to, where the proposal came from one (2026-10-20): the inputs as quoted and the
     * figures as priced that day, so the underwriter sees what the customer was shown. 404 for a case opened any other
     * way. An agent reads only the quotations it confirmed.
     */
    @GetMapping("/cases/{caseId}/quotation")
    @PreAuthorize("hasRole('REALM_AGENTS') or @access.can('underwriting.view')")
    public ResponseEntity<CaseQuotationResponse> getCaseQuotation(@PathVariable UUID caseId,
                                                                  @AuthenticationPrincipal Jwt jwt,
                                                                  Authentication authentication) {
        boolean isAgent = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority).anyMatch("ROLE_REALM_AGENTS"::equals);
        tz.co.nlolo.lifeplatform.underwriting.api.AcceptedQuotationView q = acceptedQuotations.forCase(caseId)
            .filter(v -> !isAgent || v.ownerSubject().equals(jwt.getSubject()))
            .orElseThrow(() -> new tz.co.nlolo.lifeplatform.underwriting.api.QuotationNotFoundException(caseId));
        return ResponseEntity.ok(CaseQuotationResponse.from(q, objectMapper));
    }

    @PostMapping("/cases/{caseId}/assessments")
    @PreAuthorize("@access.can('underwriting.assess')")
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
    @PreAuthorize("@access.can('underwriting.decide')")
    public ResponseEntity<UnderwritingCaseView> decide(@PathVariable UUID caseId,
                                                        @Valid @RequestBody DecideRequest request,
                                                        @AuthenticationPrincipal Jwt jwt,
                                                        Authentication authentication) {
        boolean senior = currentAccess.can(Permission.UNDERWRITING_DECIDE_AGAINST);
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
    @PreAuthorize("hasRole('REALM_AGENTS') or @access.can('underwriting.view')")
    public ResponseEntity<AnnuityChoice> getAnnuityChoice(@PathVariable UUID caseId) {
        requireAgentScope(caseId);
        return underwritingApi.annuityChoice(caseId).map(ResponseEntity::ok)
            .orElseThrow(() -> new AnnuityChoiceNotFoundException(caseId));
    }

    /** Change an annuity case's choice before it is decided. Whoever may open a case may change it. */
    @PutMapping("/cases/{caseId}/annuity-choice")
    @PreAuthorize("hasRole('REALM_AGENTS') or @access.can('underwriting.propose')")
    public ResponseEntity<AnnuityChoice> recordAnnuityChoice(@PathVariable UUID caseId,
                                                             @RequestBody OpenCaseRequest.AnnuityChoiceDto request,
                                                             @AuthenticationPrincipal Jwt jwt) {
        requireAgentScope(caseId);
        return ResponseEntity.ok(underwritingApi.recordAnnuityChoice(caseId, request.toApi(), jwt.getSubject()));
    }

    /**
     * A deferred annuity case's retirement age and target date (product step 5 D2). 404
     * DEFERRED_ANNUITY_CHOICE_NOT_FOUND when there is none -- also the answer for every other case.
     */
    @GetMapping("/cases/{caseId}/deferred-annuity-choice")
    @PreAuthorize("hasRole('REALM_AGENTS') or @access.can('underwriting.view')")
    public ResponseEntity<DeferredAnnuityChoice> getDeferredAnnuityChoice(@PathVariable UUID caseId) {
        requireAgentScope(caseId);
        return underwritingApi.deferredAnnuityChoice(caseId).map(ResponseEntity::ok)
            .orElseThrow(() -> new DeferredAnnuityChoiceNotFoundException(caseId));
    }

    /** Change a deferred annuity case's retirement age before it is decided. Whoever may open a case may change it. */
    @PutMapping("/cases/{caseId}/deferred-annuity-choice")
    @PreAuthorize("hasRole('REALM_AGENTS') or @access.can('underwriting.propose')")
    public ResponseEntity<DeferredAnnuityChoice> recordDeferredAnnuityChoice(@PathVariable UUID caseId,
                                                                             @RequestBody OpenCaseRequest.DeferredAnnuityDto request,
                                                                             @AuthenticationPrincipal Jwt jwt) {
        requireAgentScope(caseId);
        return ResponseEntity.ok(underwritingApi.recordDeferredAnnuityChoice(caseId, request.age(), jwt.getSubject()));
    }

    /**
     * A unit-linked case's fund split, premium and sum assured (product step 6). 404 UNIT_LINKED_CHOICE_NOT_FOUND
     * when there is none -- also the answer for every other case.
     */
    @GetMapping("/cases/{caseId}/unit-linked-choice")
    @PreAuthorize("hasRole('REALM_AGENTS') or @access.can('underwriting.view')")
    public ResponseEntity<UnitLinkedChoice> getUnitLinkedChoice(@PathVariable UUID caseId) {
        requireAgentScope(caseId);
        return underwritingApi.unitLinkedChoice(caseId).map(ResponseEntity::ok)
            .orElseThrow(() -> new UnitLinkedChoiceNotFoundException(caseId));
    }

    /** Record or replace a unit-linked case's choice before it is decided. Whoever may open a case may change it. */
    @PutMapping("/cases/{caseId}/unit-linked-choice")
    @PreAuthorize("hasRole('REALM_AGENTS') or @access.can('underwriting.propose')")
    public ResponseEntity<UnitLinkedChoice> recordUnitLinkedChoice(@PathVariable UUID caseId,
                                                                   @RequestBody UnitLinkedChoice request,
                                                                   @AuthenticationPrincipal Jwt jwt) {
        requireAgentScope(caseId);
        return ResponseEntity.ok(underwritingApi.recordUnitLinkedChoice(caseId, request, jwt.getSubject()));
    }

    /**
     * A funeral case's plan, dependants and a fresh quote of the family (family funeral cover). 404
     * FUNERAL_APPLICATION_NOT_FOUND when there is none -- also the answer for every other case.
     */
    @GetMapping("/cases/{caseId}/funeral-application")
    @PreAuthorize("hasRole('REALM_AGENTS') or @access.can('underwriting.view')")
    public ResponseEntity<FuneralApplication> getFuneralApplication(@PathVariable UUID caseId) {
        requireAgentScope(caseId);
        return underwritingApi.funeralApplication(caseId).map(ResponseEntity::ok)
            .orElseThrow(() -> new FuneralApplicationNotFoundException(caseId));
    }

    /** Record or replace a funeral case's plan and dependants before it is decided. Whoever may open a case may change it. */
    @PutMapping("/cases/{caseId}/funeral-application")
    @PreAuthorize("hasRole('REALM_AGENTS') or @access.can('underwriting.propose')")
    public ResponseEntity<FuneralApplication> recordFuneralApplication(@PathVariable UUID caseId,
                                                                       @Valid @RequestBody FuneralApplicationRequest request,
                                                                       @AuthenticationPrincipal Jwt jwt) {
        requireAgentScope(caseId);
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
    @PreAuthorize("hasRole('REALM_AGENTS') or @access.can('underwriting.view')")
    public ResponseEntity<List<BeneficiaryNomination>> listCaseBeneficiaries(@PathVariable UUID caseId) {
        return ResponseEntity.ok(requireAgentScope(caseId).beneficiaries());
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
    @PreAuthorize("hasRole('REALM_AGENTS') or @access.can('underwriting.view')")
    public ResponseEntity<GroupProposalResponseDto> getGroupProposal(@PathVariable UUID caseId) {
        GroupProposal proposal = requireAgentScope(caseId).groupProposal();
        return proposal != null
            ? ResponseEntity.ok(GroupProposalResponseDto.from(proposal))
            : ResponseEntity.notFound().build();
    }

    @PostMapping("/cases/{caseId}/referral")
    @PreAuthorize("@access.can('underwriting.refer')")
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
    @PreAuthorize("hasRole('REALM_AGENTS') or @access.can('underwriting.propose')")
    public ResponseEntity<MedicalDisclosureView> recordDisclosures(@PathVariable UUID caseId,
            @Valid @RequestBody RecordDisclosuresRequest request, @AuthenticationPrincipal Jwt jwt) {
        requireAgentScope(caseId);
        MedicalDisclosureView view = underwritingApi.recordDisclosures(caseId,
            request.toApiAnswers(), jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @GetMapping("/cases/{caseId}/disclosures")
    @PreAuthorize("hasRole('REALM_AGENTS') or @access.can('underwriting.view')")
    public ResponseEntity<List<MedicalDisclosureView>> listDisclosures(@PathVariable UUID caseId) {
        requireAgentScope(caseId);
        return ResponseEntity.ok(underwritingApi.listDisclosures(caseId));
    }

    // ---- Returned for amendment (2026-10-20) ------------------------------------------------------------------------

    /**
     * Return the proposal to the agent for amendment, with what is wrong and what to change -- one or more reasons, each
     * a category and the underwriter's own two sentences. From OPEN or IN_REVIEW only; the case then waits as RETURNED
     * and cannot be decided until the agent resubmits it.
     */
    @PostMapping("/cases/{caseId}/return")
    @PreAuthorize("@access.can('underwriting.decide')")
    public ResponseEntity<UnderwritingCaseView> returnForAmendment(@PathVariable UUID caseId,
                                                                   @Valid @RequestBody AmendmentRequests.ReturnRequest request,
                                                                   @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(amendments.returnForAmendment(caseId,
            request.reasons().stream().map(AmendmentRequests.Reason::toApi).toList(), request.note(), jwt.getSubject()));
    }

    /** The agent amends the contract on a returned or postponed proposal. Recorded, field by field, in its history. */
    @PutMapping("/cases/{caseId}/terms")
    @PreAuthorize("hasRole('REALM_AGENTS')")
    public ResponseEntity<UnderwritingCaseView> amendTerms(@PathVariable UUID caseId,
                                                           @Valid @RequestBody AmendmentRequests.TermsRequest request,
                                                           @AuthenticationPrincipal Jwt jwt) {
        requireAgentScope(caseId);
        return ResponseEntity.ok(amendments.amendTerms(caseId, request.toApi(), jwt.getSubject()));
    }

    /**
     * The agent corrects the client's own record on a returned or postponed proposal (user, 2026-10-20). A changed name
     * or date of birth on a verified client sends their KYC back to PENDING.
     */
    @PutMapping("/cases/{caseId}/applicant")
    @PreAuthorize("hasRole('REALM_AGENTS')")
    public ResponseEntity<tz.co.nlolo.lifeplatform.party.api.PartyDetailView> amendApplicant(@PathVariable UUID caseId,
            @Valid @RequestBody AmendmentRequests.ApplicantRequest request, @AuthenticationPrincipal Jwt jwt) {
        requireAgentScope(caseId);
        return ResponseEntity.ok(amendments.amendApplicant(caseId, request.toRegistration(), jwt.getSubject()));
    }

    /** The agent sends a returned or postponed proposal back to the queue: the same case, its next round. */
    @PostMapping("/cases/{caseId}/resubmit")
    @PreAuthorize("hasRole('REALM_AGENTS')")
    public ResponseEntity<UnderwritingCaseView> resubmit(@PathVariable UUID caseId,
                                                         @Valid @RequestBody(required = false) AmendmentRequests.ResubmitRequest request,
                                                         @AuthenticationPrincipal Jwt jwt) {
        requireAgentScope(caseId);
        return ResponseEntity.ok(amendments.resubmit(caseId, request == null ? null : request.note(), jwt.getSubject()));
    }

    /** Every return, amendment and resubmission on the case, oldest first -- for the underwriter and the agent alike. */
    @GetMapping("/cases/{caseId}/history")
    @PreAuthorize("hasRole('REALM_AGENTS') or @access.can('underwriting.view')")
    public ResponseEntity<List<ProposalAmendmentApi.CaseEventView>> history(@PathVariable UUID caseId) {
        requireAgentScope(caseId);
        return ResponseEntity.ok(amendments.history(caseId));
    }

    /**
     * The calling agent's "Needs your action": proposals returned to them or postponed, and those declined in the last
     * {@value #DECLINES_SHOWN_DAYS} days with the reason. Scoped by construction to the agent's own clients.
     */
    @GetMapping("/needs-action")
    @PreAuthorize("hasRole('REALM_AGENTS')")
    public ResponseEntity<List<ProposalAmendmentApi.NeedsAction>> needsAction(@AuthenticationPrincipal Jwt jwt) {
        Instant since = LocalDate.now(CIVIL).minusDays(DECLINES_SHOWN_DAYS).atStartOfDay(CIVIL).toInstant();
        return ResponseEntity.ok(amendments.needsAction(partyApi.partyIdsRegisteredBy(jwt.getSubject()), since));
    }

    /**
     * What is waiting on an underwriter, most recently arrived first (2026-10-20) -- a resubmission counts as arriving
     * again, so a proposal an agent has just corrected is at the top rather than wherever it was first opened. With
     * {@code resubmittedOnly}, the ones that came back from agents alone.
     */
    @GetMapping("/arrivals")
    @PreAuthorize("@access.can('underwriting.view')")
    public ResponseEntity<ArrivalsResponse> arrivals(@RequestParam(defaultValue = "false") boolean resubmittedOnly,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int pageSize) {
        Page<ProposalAmendmentApi.Arrival> result = amendments.arrivals(resubmittedOnly,
            PageRequest.of(Math.max(0, page), Math.min(Math.max(1, pageSize), 100)));
        return ResponseEntity.ok(new ArrivalsResponse(result.getContent(),
            new UnderwritingCaseSearchResponse.PageMetaDto(result.getNumber(), result.getSize(),
                (int) result.getTotalElements())));
    }

    /** {@code GET /underwriting/arrivals}: the rows, and the page in the shape the case list already uses. */
    public record ArrivalsResponse(List<ProposalAmendmentApi.Arrival> items,
                                   UnderwritingCaseSearchResponse.PageMetaDto page) {}

    /** How long a decline stays on the agent's list after it is decided: long enough to have told the customer. */
    static final int DECLINES_SHOWN_DAYS = 30;

    /**
     * An agents-realm caller reads and acts only on cases whose applicant it registered -- "my clients", as the case
     * list scopes it (2026-10-20). Anything else is the 404 an absent case is, so another agent's case, and the reasons
     * on it, cannot even be confirmed to exist. Staff pass through. Returns the case, so callers need not read it twice.
     */
    private UnderwritingCaseView requireAgentScope(UUID caseId) {
        UnderwritingCaseView view = underwritingApi.getCase(caseId);
        org.springframework.security.core.Authentication auth =
            org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        boolean agent = auth != null && auth.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority).anyMatch("ROLE_REALM_AGENTS"::equals);
        if (agent && auth.getPrincipal() instanceof Jwt jwt
                && !partyApi.partyIdsRegisteredBy(jwt.getSubject()).contains(view.applicantPartyId())) {
            throw new tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseNotFoundException(caseId);
        }
        return view;
    }
}
