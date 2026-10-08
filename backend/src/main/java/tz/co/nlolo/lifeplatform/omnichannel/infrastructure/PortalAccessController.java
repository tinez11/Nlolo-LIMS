package tz.co.nlolo.lifeplatform.omnichannel.infrastructure;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.omnichannel.api.PortalAccessRefusedException;
import tz.co.nlolo.lifeplatform.omnichannel.api.PortalAccessView;
import tz.co.nlolo.lifeplatform.omnichannel.api.PortalInvite;
import tz.co.nlolo.lifeplatform.omnichannel.application.PortalAccessService;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyDetailView;

import java.util.Map;
import java.util.UUID;

/**
 * Customer-portal access (2026-10-08, the customer portal design step 1). Staff read a client's access; customer service
 * and admins invite, re-send and revoke. The customer's own sign-in lands on {@code GET /customer/me}, which is also
 * what marks the invite used.
 */
@RestController
public class PortalAccessController {

    private static final String CAN_INVITE =
        "hasRole('REALM_STAFF') and (hasRole('CUSTOMER_SERVICE_REP') or hasRole('ADMIN'))";

    private final PortalAccessService service;
    private final PartyApi partyApi;

    public PortalAccessController(PortalAccessService service, PartyApi partyApi) {
        this.service = service;
        this.partyApi = partyApi;
    }

    @GetMapping("/parties/{partyId}/portal-access")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<PortalAccessView> access(@PathVariable UUID partyId) {
        return ResponseEntity.ok(service.access(partyId));
    }

    @PostMapping("/parties/{partyId}/portal-access")
    @PreAuthorize(CAN_INVITE)
    public ResponseEntity<PortalInvite> invite(@PathVariable UUID partyId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.invite(partyId, staffName(jwt)));
    }

    @PostMapping("/parties/{partyId}/portal-access/resend")
    @PreAuthorize(CAN_INVITE)
    public ResponseEntity<PortalInvite> resend(@PathVariable UUID partyId) {
        return ResponseEntity.ok(service.resend(partyId));
    }

    @DeleteMapping("/parties/{partyId}/portal-access")
    @PreAuthorize(CAN_INVITE)
    public ResponseEntity<PortalAccessView> revoke(@PathVariable UUID partyId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(service.revoke(partyId, staffName(jwt)));
    }

    /**
     * Who the signed-in customer is: the client record their token's {@code party_id} names, and nothing a request
     * could choose. The portal's first call after sign-in, so it also marks the invite used.
     */
    @GetMapping("/customer/me")
    @PreAuthorize("hasRole('REALM_CUSTOMERS')")
    public ResponseEntity<Map<String, Object>> me(@AuthenticationPrincipal Jwt jwt) {
        String claim = jwt.getClaimAsString("party_id");
        if (claim == null) {
            throw new AccessDeniedException("Customer token carries no party_id claim");
        }
        UUID partyId = UUID.fromString(claim);
        PartyDetailView party = partyApi.getPartyDetail(partyId);
        service.signedIn(partyId);
        java.util.LinkedHashMap<String, Object> me = new java.util.LinkedHashMap<>();
        me.put("partyId", partyId);
        me.put("displayName", party.displayName());
        me.put("email", party.email());
        me.put("phoneNumber", party.phoneNumber());
        return ResponseEntity.ok(me);
    }

    @ExceptionHandler(PortalAccessRefusedException.class)
    public ProblemDetail refused(PortalAccessRefusedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            e.conflict() ? HttpStatus.CONFLICT : HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        problem.setProperty("errorCode", e.conflict() ? "PORTAL_ACCESS_EXISTS" : "PORTAL_ACCESS_REFUSED");
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }

    /** Who acted, as people read it: the staff member's name where the token carries one. */
    private static String staffName(Jwt jwt) {
        String name = jwt.getClaimAsString("name");
        return name != null && !name.isBlank() ? name : jwt.getSubject();
    }
}
