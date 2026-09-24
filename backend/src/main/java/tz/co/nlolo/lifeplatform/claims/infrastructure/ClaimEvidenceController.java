package tz.co.nlolo.lifeplatform.claims.infrastructure;

import tz.co.nlolo.lifeplatform.AllowedDocumentContentTypes;
import tz.co.nlolo.lifeplatform.FileDownloadResponses;
import tz.co.nlolo.lifeplatform.claims.api.ClaimEvidenceView;
import tz.co.nlolo.lifeplatform.claims.api.ClaimValidationException;
import tz.co.nlolo.lifeplatform.claims.api.ClaimView;
import tz.co.nlolo.lifeplatform.claims.api.ClaimsApi;
import tz.co.nlolo.lifeplatform.document.api.DocumentApi;
import tz.co.nlolo.lifeplatform.document.api.DocumentMetadataView;
import tz.co.nlolo.lifeplatform.document.api.DocumentNotFoundException;
import tz.co.nlolo.lifeplatform.document.api.DocumentType;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
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
    private final PolicyApi policyApi;

    public ClaimEvidenceController(ClaimsApi claimsApi, DocumentApi documentApi, PolicyApi policyApi) {
        this.claimsApi = claimsApi;
        this.documentApi = documentApi;
        this.policyApi = policyApi;
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
        ClaimController.enforceAgentOwnClaimOnly(policyApi, claim, jwt, authentication);

        String contentType = allowedContentTypeOrThrow(file.getContentType());

        String documentRef;
        try {
            documentRef = documentApi.upload("claim:" + claimId, DocumentType.CLAIM_EVIDENCE, jwt.getSubject(),
                file.getInputStream(), file.getSize(), contentType, file.getOriginalFilename());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded evidence file", e);
        }

        ClaimEvidenceView evidence = claimsApi.attachEvidence(claimId, documentRef, description, jwt.getSubject(),
            ClaimController.displayName(jwt));
        return ResponseEntity.status(HttpStatus.CREATED).body(ClaimEvidenceResponseDto.from(evidence));
    }

    /**
     * Delegates the actual normalization/allowlisting to the shared
     * {@link AllowedDocumentContentTypes} (also used by {@code party}'s KYC evidence upload), but
     * keeps claims' OWN rejection shape: 422 via {@code ClaimValidationException} ->
     * {@code CLAIM_VALIDATION_FAILED} ({@code ClaimExceptionHandler}), the module's established
     * shape for "the request was well-formed but its content is not acceptable" -- a bare
     * {@code IllegalArgumentException} would fall through to {@code GlobalExceptionHandler}'s 400
     * instead, the wrong status/error code for this module's semantic rejection.
     */
    private static String allowedContentTypeOrThrow(String rawContentType) {
        try {
            return AllowedDocumentContentTypes.normalizeOrThrow(rawContentType);
        } catch (IllegalArgumentException e) {
            throw new ClaimValidationException(e.getMessage());
        }
    }

    @GetMapping("/claims/{claimId}/evidence")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<List<ClaimEvidenceResponseDto>> listEvidence(@PathVariable UUID claimId,
            @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        ClaimView claim = claimsApi.getClaim(claimId);
        ClaimController.enforceCustomerOwnClaimOnly(claim, jwt, authentication);
        ClaimController.enforceAgentOwnClaimOnly(policyApi, claim, jwt, authentication);

        List<ClaimEvidenceResponseDto> evidence = claimsApi.listEvidence(claimId).stream()
            .map(ClaimEvidenceResponseDto::from).toList();
        return ResponseEntity.ok(evidence);
    }

    /**
     * Two independent checks, and the second is not redundant. The first authorizes the CLAIM;
     * without the second, a different caller-supplied identifier -- documentRef -- still selects
     * the resource, so owning claim A would be enough to fetch claim B's evidence. That is M7's
     * nested-resource IDOR exactly. A mismatch is reported as 404 rather than 403, identical to a
     * nonexistent ref, so a caller cannot learn that someone else's document exists.
     */
    @GetMapping("/claims/{claimId}/evidence/{documentRef}")
    @PreAuthorize("hasRole('REALM_CUSTOMERS') or hasRole('REALM_AGENTS') or hasRole('REALM_STAFF')")
    public ResponseEntity<byte[]> downloadEvidence(@PathVariable UUID claimId,
            @PathVariable String documentRef,
            @AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        ClaimView claim = claimsApi.getClaim(claimId);
        ClaimController.enforceCustomerOwnClaimOnly(claim, jwt, authentication);
        ClaimController.enforceAgentOwnClaimOnly(policyApi, claim, jwt, authentication);

        DocumentMetadataView metadata = documentApi.getMetadata(documentRef);
        if (!("claim:" + claimId).equals(metadata.ownerContext())) {
            // Same exception and same message as a nonexistent ref (Task 1), so "not yours" and
            // "does not exist" are indistinguishable to the caller.
            throw new DocumentNotFoundException("No document found for ref " + documentRef);
        }

        return FileDownloadResponses.fileResponse(documentApi.download(documentRef), metadata.contentType(),
            metadata.fileName(), metadata.documentRef());
    }
}
