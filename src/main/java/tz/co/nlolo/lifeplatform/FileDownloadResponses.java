package tz.co.nlolo.lifeplatform;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
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

    /**
     * Every branch below turns an untrustworthy stored value into a working response, because a
     * download is a {@code GET} on a path that is entirely valid and must not fail on the shape of
     * data recorded long ago by some other code path.
     *
     * <p><b>The {@code InvalidMediaTypeException} catch is not paranoia.</b> {@code contentType}
     * originates as a client-supplied multipart header. {@code ClaimEvidenceController} now
     * allowlists it at upload, so nothing malformed can enter through THAT door again -- but
     * {@code DocumentController}'s generic staff download serves every document in the tenant,
     * including rows written before that allowlist existed and rows from any future upload path
     * {@code document} does not control. Without this catch, one such row 400s
     * ({@code InvalidMediaTypeException extends IllegalArgumentException}, which
     * {@code GlobalExceptionHandler} maps to 400 {@code VALIDATION_ERROR}) on every single request,
     * forever, with a status code that blames the caller for a stored-data problem. A malformed
     * value carries exactly as much information as a missing one -- namely none -- so both take the
     * same {@code application/octet-stream} fallback.
     *
     * <p><b>Blank counts as absent.</b> {@code MultipartFile.getContentType()} and
     * {@code getOriginalFilename()} can both be the empty string rather than {@code null}, and
     * {@code ""} survives a plain null check: an empty {@code contentType} would have reached
     * {@code parseMediaType("")} (which throws), and an empty {@code fileName} would have produced
     * {@code Content-Disposition: attachment; filename=""} -- a header naming no file at all.
     */
    public static ResponseEntity<byte[]> fileResponse(byte[] content, String contentType, String fileName,
            String documentRef) {
        MediaType mediaType = parseOrOctetStream(contentType);
        String filename = fileName == null || fileName.isBlank() ? documentRef : fileName;

        return ResponseEntity.ok()
            .contentType(mediaType)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(filename).build().toString())
            .body(content);
    }

    private static MediaType parseOrOctetStream(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (InvalidMediaTypeException e) {
            // A stored value we cannot parse tells us nothing about the bytes, exactly like a
            // missing one -- so serve it as an opaque download instead of failing the request.
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
