package tz.co.nlolo.lifeplatform.document.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.document.api.DocumentApi;
import tz.co.nlolo.lifeplatform.document.api.DocumentMetadataView;
import tz.co.nlolo.lifeplatform.document.api.DocumentNotFoundException;
import tz.co.nlolo.lifeplatform.document.api.DocumentType;
import tz.co.nlolo.lifeplatform.document.domain.DocumentRecord;
import tz.co.nlolo.lifeplatform.document.infrastructure.DocumentRecordRepository;
import tz.co.nlolo.lifeplatform.document.infrastructure.MinioDocumentStorage;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DocumentApiImpl implements DocumentApi {

    private final DocumentRecordRepository repository;
    private final MinioDocumentStorage storage;
    private final ApplicationEventPublisher eventPublisher;

    public DocumentApiImpl(DocumentRecordRepository repository, MinioDocumentStorage storage,
                            ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.storage = storage;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public String upload(String ownerContext, DocumentType documentType, String uploadedBy,
                          InputStream content, long contentLength, String contentType, String fileName) {
        String documentRef = UUID.randomUUID().toString();
        UUID tenantId = TenantContext.get();
        storage.put(storageKey(tenantId, documentRef), documentType, content, contentLength, contentType);

        repository.save(new DocumentRecord(documentRef, tenantId, ownerContext, documentType,
            contentType, fileName, uploadedBy, Instant.now()));

        eventPublisher.publishEvent(DomainEventEnvelope.of("document.DocumentUploaded", tenantId,
            Map.of("documentRef", documentRef, "ownerContext", ownerContext, "documentType", documentType.name())));

        return documentRef;
    }

    @Override
    public byte[] download(String documentRef) {
        DocumentRecord record = findOrThrow(documentRef);
        return storage.get(storageKey(record.getTenantId(), documentRef), record.getDocumentType());
    }

    @Override
    public DocumentMetadataView getMetadata(String documentRef) {
        DocumentRecord record = findOrThrow(documentRef);
        return new DocumentMetadataView(record.getDocumentRef(), record.getOwnerContext(), record.getDocumentType(),
            record.getContentType(), record.getFileName(), record.getUploadedBy(), record.getUploadedAt());
    }

    @Override
    public List<DocumentMetadataView> listByOwnerContext(String ownerContext) {
        return repository
            .findByTenantIdAndOwnerContextOrderByUploadedAtDescDocumentRefDesc(TenantContext.get(), ownerContext)
            .stream()
            .map(record -> new DocumentMetadataView(record.getDocumentRef(), record.getOwnerContext(),
                record.getDocumentType(), record.getContentType(), record.getFileName(),
                record.getUploadedBy(), record.getUploadedAt()))
            .toList();
    }

    private DocumentRecord findOrThrow(String documentRef) {
        DocumentRecord record = repository.findById(documentRef)
            .orElseThrow(() -> new DocumentNotFoundException("No document found for ref " + documentRef));
        // Fail-loud tenant scoping (mirrors PartyApiImpl.findPartyOrThrow): RLS is the primary
        // control, but this is genuine defense-in-depth, not a substitute for it. A cross-tenant
        // mismatch is reported identically to "doesn't exist" (docs/04-api-contracts.md §2) --
        // same exception, same message shape -- so callers can't distinguish "not found" from
        // "not yours" and infer another tenant's document exists.
        if (!record.getTenantId().equals(TenantContext.get())) {
            throw new DocumentNotFoundException("No document found for ref " + documentRef);
        }
        return record;
    }

    /**
     * Prefixes the MinIO object key with the owning tenant's ID (final-review Finding 4):
     * bare-UUID object keys give object storage itself zero tenant boundary, relevant for any
     * future presigned-URL or direct-download feature. Pre-production (no real objects exist
     * yet on this feature branch), so this changes key layout going forward with no
     * migration/backfill needed for existing objects.
     */
    private static String storageKey(UUID tenantId, String documentRef) {
        return tenantId + "/" + documentRef;
    }
}
