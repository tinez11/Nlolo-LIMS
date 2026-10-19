package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyNotFoundException;

/**
 * A quotation an applicant can take away, built before any case exists (2026-10-16).
 *
 * <p>Its own controller rather than another method on {@link UnderwritingController}, so that one's constructor --
 * which slice tests build by hand -- does not grow a collaborator only this endpoint needs.
 *
 * <p><b>An agent quotes only for its own clients.</b> The document prints the applicant's name, so an agents-realm
 * caller naming a party it did not register gets the same 404 the party-detail read gives it: the scope is "parties
 * the agent REGISTERED", as {@code GET /underwriting/cases} and {@code GET /parties} already define it.
 */
@RestController
@RequestMapping("/underwriting")
public class QuotationDocumentController {

    private final QuotationDocuments documents;
    private final PartyApi partyApi;

    public QuotationDocumentController(QuotationDocuments documents, PartyApi partyApi) {
        this.documents = documents;
        this.partyApi = partyApi;
    }

    @PostMapping(value = "/quotation-document", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<byte[]> quotationDocument(@Valid @RequestBody QuotationDocumentRequest request,
                                                    @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        boolean isAgent = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority).anyMatch("ROLE_REALM_AGENTS"::equals);
        if (isAgent && !partyApi.partyIdsRegisteredBy(jwt.getSubject()).contains(request.applicantPartyId())) {
            throw new PartyNotFoundException(request.applicantPartyId());
        }
        byte[] pdf = documents.render(request.toInput());
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename("quotation-" + request.productVersionId().toString().substring(0, 8) + ".pdf").build().toString())
            .body(pdf);
    }
}
