package tz.co.nlolo.lifeplatform.document.infrastructure;

import tz.co.nlolo.lifeplatform.document.api.DocumentMetadataView;
import tz.co.nlolo.lifeplatform.document.api.DocumentType;

import java.time.Instant;

/** {@code contentType} and {@code fileName} are nullable for documents predating document/V2. */
public record DocumentMetadataResponseDto(String documentRef, String ownerContext, DocumentType documentType,
                                           String contentType, String fileName,
                                           String uploadedBy, Instant uploadedAt) {

    public static DocumentMetadataResponseDto from(DocumentMetadataView view) {
        return new DocumentMetadataResponseDto(view.documentRef(), view.ownerContext(), view.documentType(),
            view.contentType(), view.fileName(), view.uploadedBy(), view.uploadedAt());
    }
}
