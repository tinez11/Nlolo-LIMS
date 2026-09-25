package tz.co.nlolo.lifeplatform.party.infrastructure;

import tz.co.nlolo.lifeplatform.AllowedDocumentContentTypes;
import tz.co.nlolo.lifeplatform.document.api.DocumentApi;
import tz.co.nlolo.lifeplatform.document.api.DocumentType;
import tz.co.nlolo.lifeplatform.party.api.Address;
import tz.co.nlolo.lifeplatform.party.api.GroupMembershipView;
import tz.co.nlolo.lifeplatform.party.api.IdentityDocument;
import tz.co.nlolo.lifeplatform.party.api.IndividualRegistration;
import tz.co.nlolo.lifeplatform.party.api.KycStatus;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyDetailView;
import tz.co.nlolo.lifeplatform.party.api.PartyType;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;

@RestController
public class PartyController {

    private final PartyApi partyApi;
    private final DocumentApi documentApi;

    public PartyController(PartyApi partyApi, DocumentApi documentApi) {
        this.partyApi = partyApi;
        this.documentApi = documentApi;
    }

    @PostMapping("/parties/individuals")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<PartyView> registerIndividual(@Valid @RequestBody RegisterIndividualRequest request,
                                                          @AuthenticationPrincipal Jwt jwt,
                                                          Authentication authentication) {
        IndividualRegistration registration = new IndividualRegistration(
            request.fullName(),
            request.dateOfBirth(),
            request.contactInfo() != null ? request.contactInfo().phoneNumber() : null,
            request.contactInfo() != null ? request.contactInfo().email() : null,
            request.sex(),
            request.smokerStatus(),
            new IdentityDocument(request.idType(), request.idNumber()),
            request.occupation(),
            request.occupationClass(),
            request.employerName(),
            request.nationality(),
            request.address() != null ? request.address().toAddress() : Address.none());
        PartyView view = partyApi.registerIndividual(registration, jwt.getSubject(), registrarName(jwt),
            registeringAgentPartyId(jwt, authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    /**
     * The registering AGENT's own party id — the thing that ties them to commission.
     *
     * <p>Taken from the {@code party_id} claim, the same claim {@code /agents/me} resolves an
     * agent by. A staff token carries none at all, so staff registrations record nobody, which
     * is correct: nobody brought that client in.
     *
     * <p><b>Gated on the agents realm, and that gate is load-bearing.</b> The customers realm
     * mints {@code party_id} too — it is how a customer reads their own record — so without this
     * check a customer registering an individual would record THEMSELVES as the introducing
     * agent. It would cost no commission, since they resolve to no agent, but the client record
     * would say "introduced by" a person who introduced nobody, and a wrong answer on screen is
     * worse than a blank one.
     *
     * <p><b>Not validated as an agent here, on purpose.</b> Party may not depend on distribution
     * and so cannot ask whether this party is an agent; policy answers that at issuance, where it
     * matters and where the modules line up. An agents-realm token whose party is not an agent
     * profile resolves to no agent and the policy falls back to whatever was supplied.
     */
    private static UUID registeringAgentPartyId(Jwt jwt, Authentication authentication) {
        boolean isAgent = authentication != null && authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority).anyMatch("ROLE_REALM_AGENTS"::equals);
        if (!isAgent) {
            return null;
        }
        String claim = jwt.getClaimAsString("party_id");
        if (claim == null || claim.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(claim);
        } catch (IllegalArgumentException malformed) {
            // A token we cannot read is not a reason to refuse a registration -- the client gets
            // registered with no attribution, which is the same outcome as staff registering them.
            return null;
        }
    }

    /** The registrar's name for the client record -- a label; agent scoping compares the subject. */
    private static String registrarName(Jwt jwt) {
        return tz.co.nlolo.lifeplatform.TokenNames.displayName(jwt);
    }

    @PostMapping("/parties/corporates")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<PartyView> registerCorporate(@Valid @RequestBody RegisterCorporateRequest request,
                                                         @AuthenticationPrincipal Jwt jwt,
                                                         Authentication authentication) {
        PartyView view = partyApi.registerCorporate(request.registeredName(), request.registrationNumber(),
            request.contactInfo() != null ? request.contactInfo().phoneNumber() : null,
            request.contactInfo() != null ? request.contactInfo().email() : null,
            jwt.getSubject(), registrarName(jwt), registeringAgentPartyId(jwt, authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    /**
     * Correct a person's recorded details.
     *
     * <p>PUT, not PATCH, and the request carries the whole person: this replaces the record, so
     * "clear the employer" is expressible. A PATCH shape would make an omitted field and a
     * cleared field the same wire message.
     *
     * <p><b>STAFF ONLY.</b> Registration is open to agents and to customers themselves, because
     * creating your own record is not the same act as rewriting one — an agent able to amend a
     * client after the fact could change the identity a policy was underwritten against, and a
     * customer could change theirs after a claim event.
     *
     * <p>KYC status is deliberately untouched by this. See {@code PartyApi.amendIndividual}.
     */
    @PutMapping("/parties/individuals/{partyId}")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<PartyDetailView> amendIndividual(@PathVariable UUID partyId,
                                                            @Valid @RequestBody RegisterIndividualRequest request,
                                                            @AuthenticationPrincipal Jwt jwt) {
        IndividualRegistration amended = new IndividualRegistration(
            request.fullName(),
            request.dateOfBirth(),
            request.contactInfo() != null ? request.contactInfo().phoneNumber() : null,
            request.contactInfo() != null ? request.contactInfo().email() : null,
            request.sex(),
            request.smokerStatus(),
            new IdentityDocument(request.idType(), request.idNumber()),
            request.occupation(),
            request.occupationClass(),
            request.employerName(),
            request.nationality(),
            request.address() != null ? request.address().toAddress() : Address.none());
        return ResponseEntity.ok(partyApi.amendIndividual(partyId, amended, jwt.getSubject()));
    }

    /** @see #amendIndividual — the same act, for a company or a group. */
    @PutMapping("/parties/corporates/{partyId}")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<PartyDetailView> amendCorporate(@PathVariable UUID partyId,
                                                           @Valid @RequestBody AmendCorporateRequest request,
                                                           @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(partyApi.amendOrganisation(partyId, request.registeredName(),
            request.contactInfo() != null ? request.contactInfo().phoneNumber() : null,
            request.contactInfo() != null ? request.contactInfo().email() : null,
            jwt.getSubject()));
    }

    /**
     * The full party record -- {@link PartyDetailView}, not the four-field {@link PartyView} the
     * list returns. See {@code PartyApi.getPartyDetail} for why they are separate reads.
     *
     * <p>Object-level authorization (docs/04-api-contracts.md §3), now enforced for BOTH
     * non-staff realms:
     *
     * <ul>
     *   <li>a customers-realm token may read only the party matching its own {@code party_id};
     *   <li>an agents-realm token may read only a party IT registered -- {@code createdBy} equal to
     *       its own JWT subject.
     * </ul>
     *
     * <p>The agent half was carried as an explicit deferral since M1 ("fine-grained
     * agency-hierarchy/book-of-business scoping needs agent/policy data that doesn't exist until
     * later milestones"). That data exists now, and this endpoint returning a date of birth, a
     * phone number and an email address is what made the deferral untenable: realm-role-only
     * scoping would have let any agent read the PII of every client in the tenant.
     *
     * <p>Scoped on {@code createdBy} rather than the agent's book, because the two are different
     * sets: an agent's hierarchy team may hold policies for people they never registered, and a
     * client they registered may be written by another agent. Registration is the relationship the
     * agent is accountable for, and it is what {@code GET /parties} already force-scopes on, so the
     * list and the detail agree on who "my clients" are instead of disagreeing at the drill-in.
     *
     * <p>Refused with {@link AccessDeniedException} -> 403, matching the customer branch rather
     * than masking the party as a 404. Existence is not the secret here; the PII is.
     */
    @GetMapping("/parties/{partyId}")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<PartyDetailView> getParty(@PathVariable UUID partyId, @AuthenticationPrincipal Jwt jwt,
                                               Authentication authentication) {
        boolean isCustomer = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch("ROLE_REALM_CUSTOMERS"::equals);
        if (isCustomer) {
            String ownPartyId = jwt.getClaimAsString("party_id");
            if (ownPartyId == null || !ownPartyId.equals(partyId.toString())) {
                throw new AccessDeniedException("Access denied: customer may only read their own party");
            }
        }

        PartyDetailView party = partyApi.getPartyDetail(partyId);

        boolean isAgent = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch("ROLE_REALM_AGENTS"::equals);
        if (isAgent && !jwt.getSubject().equals(party.createdBy())) {
            throw new AccessDeniedException("Access denied: agent may only read a client they registered");
        }
        return ResponseEntity.ok(party);
    }

    /**
     * There was no way to list/filter parties at all until this -- see {@code PartyApi
     * .searchParties}'s own javadoc for why that mattered (a registered-but-not-yet-KYC'd party
     * with no policy/claim/case/agent referencing it was otherwise invisible to staff). Staff use
     * this as an unrestricted KYC review queue; an agents-realm caller is force-scoped to parties
     * IT registered (its own JWT subject, never client-supplied {@code createdBy}) -- the same
     * "override the query" idiom {@code PolicyController.searchPolicies} uses for customers.
     *
     * <p>The sort is not decoration. This endpoint shipped with a bare {@code PageRequest.of(page,
     * pageSize)} and therefore no {@code ORDER BY} at all, which broke it two ways: a newly
     * registered party landed in whatever position the scan happened to yield -- in practice last,
     * so the queue buried the very thing it exists to surface -- and, worse, paginating an
     * unordered query is unsound. Postgres makes no promise that two `LIMIT/OFFSET` queries see
     * rows in the same order, so a reviewer walking page 1 -> 2 could be shown a party twice and
     * never be shown another at all. A KYC queue that can silently omit a party is a compliance
     * problem, not a cosmetic one.
     *
     * <p>{@code partyId} is the tie-breaker, and it is what makes the order TOTAL: {@code createdAt}
     * is assigned in Java by {@code Instant.now()}, so a batch registration can genuinely collide,
     * and ties in the leading key put us straight back to an undefined order for the rows that tie.
     *
     * <p>{@code partyType} is repeatable -- {@code ?partyType=CORPORATE&partyType=GROUP} -- rather
     * than a single value, because the client register is split into two working areas and the
     * second of them is corporates AND groups together. Two separate requests could not be paged
     * or totalled as one list, so the filter takes a set and the whole area stays one honest
     * pager. Omitting it entirely means every type, which is what every existing caller does.
     */
    @GetMapping("/parties")
    @PreAuthorize("hasRole('REALM_STAFF') or hasRole('REALM_AGENTS')")
    public ResponseEntity<PageResponse<PartyView>> searchParties(
            @RequestParam(required = false) KycStatus kycStatus,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) List<PartyType> partyType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        boolean isAgent = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority).anyMatch("ROLE_REALM_AGENTS"::equals);
        String effectiveCreatedBy = isAgent ? jwt.getSubject() : null;
        Page<PartyView> result = partyApi.searchParties(kycStatus, effectiveCreatedBy, q, partyType,
            PageRequest.of(page, Math.min(pageSize, 100),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("partyId"))));
        return ResponseEntity.ok(PageResponse.from(result));
    }

    /**
     * The documents filed against this party — KYC evidence today, whatever else is filed under
     * {@code party:<id>} tomorrow.
     *
     * <p>It lives here, not on {@code DocumentController}, for the reason that controller's own
     * javadoc gives: generic document access is deliberately staff-only, because authorizing a
     * document means asking the owning aggregate "is this yours?", and the document module cannot
     * ask — party, claims, policy and underwriting all declare {@code document::api}, so a
     * dependency back would be a cycle. The owning module applies its own rule instead, exactly as
     * {@code ClaimEvidenceController} does for claim evidence. That is also what lets an agent read
     * these at all: a generic endpoint could never have served them.
     *
     * <p>Same scoping as the party-detail read, for the same reason — an agents-realm caller may
     * see only a client it registered.
     *
     * <p>Metadata only, no content. Downloading still goes through the existing per-document
     * endpoints; this answers "what do we hold on this person", which nothing could answer before.
     */
    @GetMapping("/parties/{partyId}/documents")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<List<PartyDocumentResponseDto>> listPartyDocuments(@PathVariable UUID partyId,
            @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        boolean isAgent = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority).anyMatch("ROLE_REALM_AGENTS"::equals);
        if (isAgent && !partyApi.isRegisteredBy(partyId, jwt.getSubject())) {
            throw new AccessDeniedException("Access denied: agent may only read a client they registered");
        }
        // Existence check first, so an unknown party is a 404 rather than an empty list -- an empty
        // list would say "this person has no documents" about someone who does not exist.
        partyApi.getParty(partyId);
        return ResponseEntity.ok(documentApi.listByOwnerContext("party:" + partyId).stream()
            .map(PartyDocumentResponseDto::from).toList());
    }

    /**
     * A real, previously-missing upload path: {@code PartyApi.submitKycEvidence} below has always
     * required a real {@code evidenceDocumentRef}, but until this endpoint there was no way to
     * produce one for a KYC purpose at all -- {@code claims.infrastructure.ClaimEvidenceController}
     * is the only other upload path on the platform, and it is hardcoded to claim evidence. Mirrors
     * that controller's shape exactly (allowlist via the now-shared
     * {@link AllowedDocumentContentTypes}, {@code ownerContext} scoped to this owning aggregate).
     */
    @PostMapping(value = "/parties/{partyId}/kyc-evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<KycEvidenceUploadResponseDto> uploadKycEvidence(@PathVariable UUID partyId,
            @RequestPart("file") MultipartFile file, @AuthenticationPrincipal Jwt jwt) {
        String contentType = AllowedDocumentContentTypes.normalizeOrThrow(file.getContentType());
        String documentRef;
        try {
            documentRef = documentApi.upload("party:" + partyId, DocumentType.KYC_EVIDENCE, jwt.getSubject(),
                file.getInputStream(), file.getSize(), contentType, file.getOriginalFilename());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded KYC evidence file", e);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(new KycEvidenceUploadResponseDto(documentRef));
    }

    @PostMapping("/parties/{partyId}/kyc")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<Void> submitKyc(@PathVariable UUID partyId, @Valid @RequestBody KycUpdateRequest request,
                                            @AuthenticationPrincipal Jwt jwt) {
        partyApi.submitKycEvidence(partyId, request.status(), request.evidenceDocumentRef(), jwt.getSubject());
        return ResponseEntity.ok().build();
    }

    /**
     * Same unordered-pagination defect as {@code searchParties} had, and it bites harder here: a
     * group scheme can hold thousands of members, so this is the endpoint most likely to actually
     * be paged through, and an unstable order means a member can be missed on every pass. Sorted
     * oldest-first, unlike the queues -- a membership roll reads as a roll, and joining order is
     * the only order it has.
     */
    @GetMapping("/parties/{partyId}/groups/{groupId}/members")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<PageResponse<GroupMembershipView>> listGroupMembers(
            @PathVariable UUID partyId, @PathVariable UUID groupId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int pageSize) {
        Page<GroupMembershipView> result = partyApi.listGroupMembers(groupId,
            PageRequest.of(page, Math.min(pageSize, 200),
                Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("groupMembershipId"))));
        return ResponseEntity.ok(PageResponse.from(result));
    }

    @PostMapping("/parties/{partyId}/groups/{groupId}/members")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<Void> addGroupMember(@PathVariable UUID partyId, @PathVariable UUID groupId,
                                                 @Valid @RequestBody AddGroupMemberRequest request) {
        partyApi.addGroupMember(groupId, request.memberPartyId());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
