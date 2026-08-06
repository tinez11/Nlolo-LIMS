package tz.co.nlolo.lifeplatform.document.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import tz.co.nlolo.lifeplatform.document.api.DocumentType;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document_record", schema = "document")
public class DocumentRecord {

    @Id
    @Column(name = "document_ref")
    private String documentRef;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "owner_context", nullable = false)
    private String ownerContext;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false)
    private DocumentType documentType;

    @Column(name = "uploaded_by")
    private String uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    protected DocumentRecord() {}

    public DocumentRecord(String documentRef, UUID tenantId, String ownerContext, DocumentType documentType,
                           String uploadedBy, Instant uploadedAt) {
        this.documentRef = documentRef;
        this.tenantId = tenantId;
        this.ownerContext = ownerContext;
        this.documentType = documentType;
        this.uploadedBy = uploadedBy;
        this.uploadedAt = uploadedAt;
    }

    public String getDocumentRef() { return documentRef; }
    public String getOwnerContext() { return ownerContext; }
    public DocumentType getDocumentType() { return documentType; }
    public String getUploadedBy() { return uploadedBy; }
    public Instant getUploadedAt() { return uploadedAt; }
}
