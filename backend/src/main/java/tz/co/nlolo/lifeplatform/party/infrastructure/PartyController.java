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
                                                          @AuthenticationPrincipal Jwt jwt) {
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
        PartyView view = partyApi.registerIndividual(registration, jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @PostMapping("/parties/corporates")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<PartyView> registerCorporate(@Valid @RequestBody RegisterCorporateRequest request,
                                                         @AuthenticationPrincipal Jwt jwt) {
        PartyView view = partyApi.registerCorporate(request.registeredName(), request.registrationNumber(),
            request.contactInfo() != null ? request.contactInfo().phoneNumber() : null,
            request.contactInfo() != null ? request.contactInfo().email() : null,
            jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
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
     */
    @GetMapping("/parties")
    @PreAuthorize("hasRole('REALM_STAFF') or hasRole('REALM_AGENTS')")
    public ResponseEntity<PageResponse<PartyView>> searchParties(
            @RequestParam(required = false) KycStatus kycStatus,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        boolean isAgent = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority).anyMatch("ROLE_REALM_AGENTS"::equals);
        String effectiveCreatedBy = isAgent ? jwt.getSubject() : null;
        Page<PartyView> result = partyApi.searchParties(kycStatus, effectiveCreatedBy, q,
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
