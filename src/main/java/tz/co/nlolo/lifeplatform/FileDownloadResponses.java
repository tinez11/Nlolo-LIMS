package tz.co.nlolo.lifeplatform;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The Content-Type/Content-Disposition fallbacks shared by every HTTP endpoint that serves raw
 * document bytes back out of {@code DocumentApi.download} -- today the customer-facing claim-
 * evidence download ({@code claims.infrastructure.ClaimEvidenceController}), and, per Task 4, the
 * staff-only generic document download too.
 *
 * <p>Lives in the shared-kernel root package (alongside {@code TenantContext},
 * {@code DomainEventEnvelope}, {@code GlobalExceptionHandler}) rather than in
 * {@code document.infrastructure} as originally sketched: {@code document/api/package-info.java}
 * declares {@code @NamedInterface("api")}, and {@code claims}' {@code allowedDependencies} names
 * only {@code document::api} -- {@code document.infrastructure} is a DIFFERENT (unnamed) interface
 * that {@code claims} has no declared access to, so a cross-module reference from {@code claims} to
 * a class living there fails {@code ModularityTests} outright.
 *
 * <p><b>Deliberately takes primitive/String parameters, NOT {@code DocumentMetadataView} itself.</b>
 * An earlier version of this class accepted the view type directly, which -- verified empirically
 * via a failing {@code ModularityTests} run, not assumed -- creates a genuine cycle: {@code document}
 * already depends on this root package (its own {@code DocumentApiImpl} calls {@code TenantContext}/
 * {@code DomainEventEnvelope}), so this root package depending back on {@code document.api} for a
 * parameter type closes {@code document -> root -> document}. Root-level classes on this platform
 * are a one-way sink that every module may depend on; none of them depend on a business module's
 * types. Passing the three already-nullable fields as plain {@code String}/{@code byte[]} instead
 * keeps this class's only imports as JDK/Spring types, so it adds no edge to the module graph at
 * all and needs no {@code allowedDependencies} entry anywhere -- verified by
 * {@code ModularityTests}/{@code NoCircularDependencyTest} passing with this class in place.
 *
 * <p>{@code contentType}/{@code fileName} are nullable for documents predating {@code document/V2},
 * so both fallbacks below are reachable in any real deployment -- not defensive padding.
 */
public final class FileDownloadResponses {

    private FileDownloadResponses() {}

    public static ResponseEntity<byte[]> fileResponse(byte[] content, String contentType, String fileName,
            String documentRef) {
        MediaType mediaType = contentType == null
            ? MediaType.APPLICATION_OCTET_STREAM
            : MediaType.parseMediaType(contentType);
        String filename = fileName == null ? documentRef : fileName;

        return ResponseEntity.ok()
            .contentType(mediaType)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(filename).build().toString())
            .body(content);
    }
}
