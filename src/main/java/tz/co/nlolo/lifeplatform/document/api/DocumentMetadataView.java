package tz.co.nlolo.lifeplatform.document.api;

import java.time.Instant;

public record DocumentMetadataView(String documentRef, String ownerContext, DocumentType documentType,
                                    String uploadedBy, Instant uploadedAt) {}
