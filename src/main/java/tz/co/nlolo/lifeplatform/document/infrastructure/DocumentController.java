package tz.co.nlolo.lifeplatform.document.infrastructure;

import tz.co.nlolo.lifeplatform.FileDownloadResponses;
import tz.co.nlolo.lifeplatform.document.api.DocumentApi;
import tz.co.nlolo.lifeplatform.document.api.DocumentMetadataView;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Generic document access, deliberately STAFF-ONLY.
 *
 * <p>Customers and agents cannot use these endpoints, and that is a design constraint rather than
 * an oversight. Authorizing a document means answering "does this belong to you?", which only the
 * owning aggregate can answer -- and this module cannot ask it: claims, party, policy and
 * underwriting all declare {@code document::api}, so a dependency from here onto any of them is a
 * cycle {@code NoCircularDependencyTest} fails on. Customer-facing access therefore lives in the
 * owning module ({@code ClaimEvidenceController} today). Staff need no such check: they are
 * authorized tenant-wide, and cross-tenant refs are already indistinguishable from not-found
 * inside {@code DocumentApiImpl.findOrThrow}.
 *
 * <p>There is no upload endpoint here for the same reason -- an upload must be attributed to an
 * owning aggregate, which is the aggregate's own business.
 */
@RestController
public class DocumentController {

    private final DocumentApi documentApi;

    public DocumentController(DocumentApi documentApi) {
        this.documentApi = documentApi;
    }

    @GetMapping("/documents/{documentRef}")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<byte[]> download(@PathVariable String documentRef) {
        DocumentMetadataView metadata = documentApi.getMetadata(documentRef);
        return FileDownloadResponses.fileResponse(documentApi.download(documentRef), metadata.contentType(),
            metadata.fileName(), metadata.documentRef());
    }

    @GetMapping("/documents/{documentRef}/metadata")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<DocumentMetadataResponseDto> metadata(@PathVariable String documentRef) {
        return ResponseEntity.ok(DocumentMetadataResponseDto.from(documentApi.getMetadata(documentRef)));
    }
}
