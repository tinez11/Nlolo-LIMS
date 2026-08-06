package tz.co.nlolo.lifeplatform.party.infrastructure;

import tz.co.nlolo.lifeplatform.party.api.GroupMembershipView;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class PartyController {

    private final PartyApi partyApi;

    public PartyController(PartyApi partyApi) {
        this.partyApi = partyApi;
    }

    @PostMapping("/parties/individuals")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS')")
    public ResponseEntity<PartyView> registerIndividual(@RequestBody RegisterIndividualRequest request,
                                                          @AuthenticationPrincipal Jwt jwt) {
        PartyView view = partyApi.registerIndividual(request.fullName(), request.dateOfBirth(),
            request.contactInfo() != null ? request.contactInfo().phoneNumber() : null,
            request.contactInfo() != null ? request.contactInfo().email() : null,
            jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @PostMapping("/parties/corporates")
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<PartyView> registerCorporate(@RequestBody RegisterCorporateRequest request,
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
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
        }
        return ResponseEntity.ok(partyApi.getParty(partyId));
    }

    @PostMapping("/parties/{partyId}/kyc")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<Void> submitKyc(@PathVariable UUID partyId, @RequestBody KycUpdateRequest request,
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
                                                 @RequestBody AddGroupMemberRequest request) {
        partyApi.addGroupMember(groupId, request.memberPartyId());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
