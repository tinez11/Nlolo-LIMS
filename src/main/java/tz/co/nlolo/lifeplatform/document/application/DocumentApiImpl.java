package tz.co.nlolo.lifeplatform.document.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.document.api.DocumentApi;
import tz.co.nlolo.lifeplatform.document.api.DocumentMetadataView;
import tz.co.nlolo.lifeplatform.document.api.DocumentType;
import tz.co.nlolo.lifeplatform.document.domain.DocumentRecord;
import tz.co.nlolo.lifeplatform.document.infrastructure.DocumentRecordRepository;
import tz.co.nlolo.lifeplatform.document.infrastructure.MinioDocumentStorage;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;
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
                          InputStream content, long contentLength, String contentType) {
        String documentRef = UUID.randomUUID().toString();
        storage.put(documentRef, documentType, content, contentLength, contentType);

        UUID tenantId = TenantContext.get();
        repository.save(new DocumentRecord(documentRef, tenantId, ownerContext, documentType, uploadedBy, Instant.now()));

        eventPublisher.publishEvent(DomainEventEnvelope.of("document.DocumentUploaded", tenantId,
            Map.of("documentRef", documentRef, "ownerContext", ownerContext, "documentType", documentType.name())));

        return documentRef;
    }

    @Override
    public byte[] download(String documentRef) {
        DocumentRecord record = findOrThrow(documentRef);
        return storage.get(documentRef, record.getDocumentType());
    }

    @Override
    public DocumentMetadataView getMetadata(String documentRef) {
        DocumentRecord record = findOrThrow(documentRef);
        return new DocumentMetadataView(record.getDocumentRef(), record.getOwnerContext(), record.getDocumentType(),
            record.getUploadedBy(), record.getUploadedAt());
    }

    private DocumentRecord findOrThrow(String documentRef) {
        return repository.findById(documentRef)
            .orElseThrow(() -> new NoSuchElementException("No document found for ref " + documentRef));
    }
}
