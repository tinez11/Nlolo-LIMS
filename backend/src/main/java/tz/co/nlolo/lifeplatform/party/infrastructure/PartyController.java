package tz.co.nlolo.lifeplatform.party.infrastructure;

import tz.co.nlolo.lifeplatform.AllowedDocumentContentTypes;
import tz.co.nlolo.lifeplatform.document.api.DocumentApi;
import tz.co.nlolo.lifeplatform.document.api.DocumentType;
import tz.co.nlolo.lifeplatform.party.api.GroupMembershipView;
import tz.co.nlolo.lifeplatform.party.api.KycStatus;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
        PartyView view = partyApi.registerIndividual(request.fullName(), request.dateOfBirth(),
            request.contactInfo() != null ? request.contactInfo().phoneNumber() : null,
            request.contactInfo() != null ? request.contactInfo().email() : null,
            jwt.getSubject());
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

    @GetMapping("/parties/{partyId}")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<PartyView> getParty(@PathVariable UUID partyId, @AuthenticationPrincipal Jwt jwt,
                                               Authentication authentication) {
        // Object-level authorization (docs/04-api-contracts.md §3): a customers-realm
        // token may only read the party matching its own party_id claim. Agents/staff
        // are scoped by realm role alone at M1 -- fine-grained agency-hierarchy/
        // book-of-business scoping needs agent/policy data that doesn't exist until
        // later milestones, and is explicitly deferred, not silently skipped.
        boolean isCustomer = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch("ROLE_REALM_CUSTOMERS"::equals);
        if (isCustomer) {
            String ownPartyId = jwt.getClaimAsString("party_id");
            if (ownPartyId == null || !ownPartyId.equals(partyId.toString())) {
                throw new AccessDeniedException("Access denied: customer may only read their own party");
            }
        }
        return ResponseEntity.ok(partyApi.getParty(partyId));
    }

    /**
     * There was no way to list/filter parties at all until this -- see {@code PartyApi
     * .searchParties}'s own javadoc for why that mattered (a registered-but-not-yet-KYC'd party
     * with no policy/claim/case/agent referencing it was otherwise invisible to staff). Staff use
     * this as an unrestricted KYC review queue; an agents-realm caller is force-scoped to parties
     * IT registered (its own JWT subject, never client-supplied {@code createdBy}) -- the same
     * "override the query" idiom {@code PolicyController.searchPolicies} uses for customers.
     */
    @GetMapping("/parties")
    @PreAuthorize("hasRole('REALM_STAFF') or hasRole('REALM_AGENTS')")
    public ResponseEntity<PageResponse<PartyView>> searchParties(
            @RequestParam(required = false) KycStatus kycStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        boolean isAgent = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority).anyMatch("ROLE_REALM_AGENTS"::equals);
        String effectiveCreatedBy = isAgent ? jwt.getSubject() : null;
        Page<PartyView> result = partyApi.searchParties(kycStatus, effectiveCreatedBy,
            PageRequest.of(page, Math.min(pageSize, 100)));
        return ResponseEntity.ok(PageResponse.from(result));
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

    @GetMapping("/parties/{partyId}/groups/{groupId}/members")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<PageResponse<GroupMembershipView>> listGroupMembers(
            @PathVariable UUID partyId, @PathVariable UUID groupId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int pageSize) {
        Page<GroupMembershipView> result = partyApi.listGroupMembers(groupId, PageRequest.of(page, Math.min(pageSize, 200)));
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
