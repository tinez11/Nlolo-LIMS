package tz.co.nlolo.lifeplatform.party.infrastructure;

import tz.co.nlolo.lifeplatform.document.api.DocumentMetadataView;
import tz.co.nlolo.lifeplatform.document.api.DocumentType;

import java.time.Instant;

/**
 * Wire shape for {@code GET /parties/{partyId}/documents}.
 *
 * <p>A near-duplicate of {@code document.infrastructure.DocumentMetadataResponseDto}, and
 * deliberately not a reuse of it: that class lives in another module's infrastructure package, which
 * this module is not allowed to import — {@code party} declares {@code document::api}, and the
 * named-interface rule means only the {@code api} package crosses. Mapping from
 * {@link DocumentMetadataView}, which does live in that interface, is the supported path.
 *
 * <p>{@code ownerContext} is deliberately omitted. It is always {@code "party:<the id in the URL>"}
 * here, so returning it would be noise, and it is an internal addressing convention rather than
 * something a client should learn to construct.
 */
public record PartyDocumentResponseDto(
    String documentRef,
    DocumentType documentType,
    String contentType,
    String fileName,
    String uploadedBy,
    Instant uploadedAt) {

    public static PartyDocumentResponseDto from(DocumentMetadataView view) {
        return new PartyDocumentResponseDto(view.documentRef(), view.documentType(), view.contentType(),
            view.fileName(), view.uploadedBy(), view.uploadedAt());
    }
}
