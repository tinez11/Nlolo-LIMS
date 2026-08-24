package tz.co.nlolo.lifeplatform.claims.infrastructure;

import tz.co.nlolo.lifeplatform.FileDownloadResponses;
import tz.co.nlolo.lifeplatform.claims.api.ClaimEvidenceView;
import tz.co.nlolo.lifeplatform.claims.api.ClaimValidationException;
import tz.co.nlolo.lifeplatform.claims.api.ClaimView;
import tz.co.nlolo.lifeplatform.claims.api.ClaimsApi;
import tz.co.nlolo.lifeplatform.document.api.DocumentApi;
import tz.co.nlolo.lifeplatform.document.api.DocumentMetadataView;
import tz.co.nlolo.lifeplatform.document.api.DocumentNotFoundException;
import tz.co.nlolo.lifeplatform.document.api.DocumentType;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
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
import java.util.Locale;
import java.util.Set;
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

    /**
     * The closed set of media types evidence may be uploaded with, and therefore the closed set
     * {@code openapi-document.yaml}'s two binary {@code 200} responses declare -- the two are one
     * decision and must be changed together.
     *
     * <p>{@code MultipartFile.getContentType()} is a CLIENT-SUPPLIED header that Tomcat does not
     * validate in any way: it will happily hand back {@code text/html}, {@code not a media type},
     * or {@code ""}. Persisting it unchecked had two consequences. (1) Whatever arrived was echoed
     * straight back out of the download endpoints by {@code FileDownloadResponses}, so an uploaded
     * {@code text/html} "photo" rendered as a page in the victim's browser -- stored XSS through a
     * document store. (2) A value that is not parseable as a media type at all could not be turned
     * into a {@code Content-Type} header on the way out, permanently breaking that one document's
     * download (see {@code FileDownloadResponses}' own fallback, which is the defence-in-depth half
     * of this fix for rows this allowlist never saw).
     *
     * <p>{@code application/octet-stream} is in the set as the genuine "we do not know what this
     * is" value, NOT as a wildcard escape hatch: it renders as a download in every browser, so it
     * carries none of the inline-rendering risk the other entries' allowance is weighed against.
     */
    private static final Set<String> ALLOWED_EVIDENCE_CONTENT_TYPES =
        Set.of("image/jpeg", "image/png", "application/pdf", "application/octet-stream");

    /** Sorted for a deterministic error message; the set above is the authority. */
    private static final String ALLOWED_EVIDENCE_CONTENT_TYPES_DISPLAY =
        ALLOWED_EVIDENCE_CONTENT_TYPES.stream().sorted().reduce((a, b) -> a + ", " + b).orElseThrow();

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

        String contentType = allowedContentTypeOrThrow(file.getContentType());

        String documentRef;
        try {
            documentRef = documentApi.upload("claim:" + claimId, DocumentType.CLAIM_EVIDENCE, jwt.getSubject(),
                file.getInputStream(), file.getSize(), contentType, file.getOriginalFilename());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded evidence file", e);
        }

        ClaimEvidenceView evidence = claimsApi.attachEvidence(claimId, documentRef, description, jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(ClaimEvidenceResponseDto.from(evidence));
    }

    /**
     * Normalizes and allowlists the client-supplied part {@code Content-Type}, returning the exact
     * value to persist. Rejections are 422 via {@code claims}' own
     * {@link ClaimValidationException} -> {@code CLAIM_VALIDATION_FAILED} mapping
     * ({@code ClaimExceptionHandler}), the module's established shape for "the request was
     * well-formed but its content is not acceptable"; a bare {@code IllegalArgumentException} would
     * have fallen through to {@code GlobalExceptionHandler}'s 400 instead, which is the wrong
     * status and the wrong error code for a semantic rejection.
     *
     * <p>Parameters are DROPPED, not merely ignored: {@code image/jpeg;charset=<script>} is stored
     * as {@code image/jpeg}, so what lands in {@code document_record.content_type} is always one of
     * exactly four short, parseable literals. A missing or blank part header is the honest "unknown"
     * case and becomes {@code application/octet-stream} rather than a rejection -- a client that
     * simply sends no {@code Content-Type} on the part is not doing anything wrong.
     */
    private static String allowedContentTypeOrThrow(String rawContentType) {
        if (rawContentType == null || rawContentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
        MediaType parsed;
        try {
            parsed = MediaType.parseMediaType(rawContentType);
        } catch (InvalidMediaTypeException e) {
            throw new ClaimValidationException("Evidence content type '" + rawContentType
                + "' is not a valid media type. Allowed: " + ALLOWED_EVIDENCE_CONTENT_TYPES_DISPLAY);
        }
        String normalized = (parsed.getType() + "/" + parsed.getSubtype()).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EVIDENCE_CONTENT_TYPES.contains(normalized)) {
            throw new ClaimValidationException("Evidence content type '" + normalized
                + "' is not allowed. Allowed: " + ALLOWED_EVIDENCE_CONTENT_TYPES_DISPLAY);
        }
        return normalized;
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
