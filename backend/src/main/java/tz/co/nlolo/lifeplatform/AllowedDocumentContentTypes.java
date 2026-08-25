package tz.co.nlolo.lifeplatform;

import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

import java.util.Locale;
import java.util.Set;

/**
 * The closed set of media types ANY document upload on this platform may be stored with, and
 * therefore the closed set every download endpoint's binary response declares -- shared by every
 * upload path (today {@code claims.infrastructure.ClaimEvidenceController}, and
 * {@code party.infrastructure.PartyController}'s KYC evidence upload) so the allowlist and its
 * normalization logic exist in exactly one place rather than being copy-pasted per module.
 *
 * <p>Lives in the shared-kernel root package for the same reason as {@link FileDownloadResponses}:
 * every business module already depends on this package (TenantContext, DomainEventEnvelope), so
 * this package must never depend back on a business module's types -- this class takes and returns
 * only JDK/Spring types, adding no edge to the module graph.
 *
 * <p>{@code MultipartFile.getContentType()} is a CLIENT-SUPPLIED header Tomcat does not validate at
 * all: it will happily hand back {@code text/html}, {@code not a media type}, or {@code ""}.
 * Persisting it unchecked lets an uploaded {@code text/html} "photo" render as a page in a later
 * viewer's browser -- stored XSS through a document store -- which is why every upload path
 * allowlists through here rather than trusting the header. {@code application/octet-stream} is in
 * the set as the genuine "we do not know what this is" value, NOT a wildcard escape hatch: it
 * renders as a download in every browser, carrying none of the inline-rendering risk the other
 * three entries' allowance is weighed against.
 *
 * <p>Each caller decides its OWN rejection exception/status (claims maps a rejection to its
 * {@code ClaimValidationException} -> 422; this class only normalizes and reports whether a value
 * is allowed, throwing a plain {@link IllegalArgumentException} that a caller with no more specific
 * mapping can let fall through to {@code GlobalExceptionHandler}'s existing 400 VALIDATION_ERROR).
 */
public final class AllowedDocumentContentTypes {

    public static final Set<String> ALLOWED =
        Set.of("image/jpeg", "image/png", "application/pdf", "application/octet-stream");

    /** Sorted for a deterministic error message; {@link #ALLOWED} is the authority. */
    public static final String ALLOWED_DISPLAY =
        ALLOWED.stream().sorted().reduce((a, b) -> a + ", " + b).orElseThrow();

    private AllowedDocumentContentTypes() {}

    /**
     * A missing or blank value is the honest "unknown" case, not a rejection -- a client that
     * simply sends no {@code Content-Type} on the part is not doing anything wrong, and becomes
     * {@code application/octet-stream}. Parameters are dropped, not merely ignored:
     * {@code image/jpeg;charset=<script>} normalizes to {@code image/jpeg}, so what a caller
     * persists is always one of exactly four short, parseable literals.
     */
    public static String normalizeOrThrow(String rawContentType) {
        if (rawContentType == null || rawContentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
        MediaType parsed;
        try {
            parsed = MediaType.parseMediaType(rawContentType);
        } catch (InvalidMediaTypeException e) {
            throw new IllegalArgumentException("Content type '" + rawContentType
                + "' is not a valid media type. Allowed: " + ALLOWED_DISPLAY);
        }
        String normalized = (parsed.getType() + "/" + parsed.getSubtype()).toLowerCase(Locale.ROOT);
        if (!ALLOWED.contains(normalized)) {
            throw new IllegalArgumentException("Content type '" + normalized
                + "' is not allowed. Allowed: " + ALLOWED_DISPLAY);
        }
        return normalized;
    }
}
