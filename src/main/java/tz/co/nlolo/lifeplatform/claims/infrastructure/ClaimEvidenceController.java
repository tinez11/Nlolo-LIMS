package tz.co.nlolo.lifeplatform.claims.infrastructure;

import tz.co.nlolo.lifeplatform.claims.api.ClaimEvidenceView;
import tz.co.nlolo.lifeplatform.claims.api.ClaimView;
import tz.co.nlolo.lifeplatform.claims.api.ClaimsApi;
import tz.co.nlolo.lifeplatform.document.api.DocumentApi;
import tz.co.nlolo.lifeplatform.document.api.DocumentType;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;

/**
 * The two {@code /claims/{claimId}/evidence} operations. There is no REST {@code DocumentController}
 * yet anywhere on this platform (document upload/download is only exercised via {@code DocumentApi}
 * directly today, e.g. in {@code ClaimEvidenceIntegrationTest}) -- so this controller is the ONLY
 * HTTP path onto evidence for a claim, and it owns both halves: uploading the multipart file
 * through {@code DocumentApi} (claims' {@code allowedDependencies} already include
 * {@code document::api} -- {@code ClaimsApiImpl.attachEvidence} already depends on it for
 * {@code getMetadata}) and then linking the resulting {@code documentRef} to the claim via
 * {@code ClaimsApi.attachEvidence}.
 */
@RestController
public class ClaimEvidenceController {

    private final ClaimsApi claimsApi;
    private final DocumentApi documentApi;

    public ClaimEvidenceController(ClaimsApi claimsApi, DocumentApi documentApi) {
        this.claimsApi = claimsApi;
        this.documentApi = documentApi;
    }

    /**
     * Object-level ownership check applied here too, even though this task's brief's role table
     * only lists it for the GET evidence endpoint below -- see
     * {@code ClaimController.registerClaim}'s javadoc for the same reasoning: without it, any
     * customer token could attach evidence to a claim it does not own, which is exactly the kind
     * of Broken-Object-Level-Authorization gap docs/04-api-contracts.md:41 calls out as mandatory
     * to close on every customer-scoped endpoint.
     */
    @PostMapping(value = "/claims/{claimId}/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<ClaimEvidenceResponseDto> attachEvidence(@PathVariable UUID claimId,
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "description", required = false) String description,
            @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        ClaimView claim = claimsApi.getClaim(claimId);
        ClaimController.enforceCustomerOwnClaimOnly(claim, jwt, authentication);

        String documentRef;
        try {
            documentRef = documentApi.upload("claim:" + claimId, DocumentType.CLAIM_EVIDENCE, jwt.getSubject(),
                file.getInputStream(), file.getSize(), file.getContentType(), file.getOriginalFilename());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded evidence file", e);
        }

        ClaimEvidenceView evidence = claimsApi.attachEvidence(claimId, documentRef, description, jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(ClaimEvidenceResponseDto.from(evidence));
    }

    @GetMapping("/claims/{claimId}/evidence")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<List<ClaimEvidenceResponseDto>> listEvidence(@PathVariable UUID claimId,
            @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        ClaimView claim = claimsApi.getClaim(claimId);
        ClaimController.enforceCustomerOwnClaimOnly(claim, jwt, authentication);

        List<ClaimEvidenceResponseDto> evidence = claimsApi.listEvidence(claimId).stream()
            .map(ClaimEvidenceResponseDto::from).toList();
        return ResponseEntity.ok(evidence);
    }
}
