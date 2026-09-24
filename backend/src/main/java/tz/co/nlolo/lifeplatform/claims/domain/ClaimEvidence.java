package tz.co.nlolo.lifeplatform.claims.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/** Maps {@code claims.claim_evidence} 1:1 (added by claims/V2). {@code documentRef} is an opaque
 * string returned by {@code DocumentApi.upload} -- never an FK, per docs/06:29. No
 * {@code @Version}: no version column, and evidence rows are never mutated after upload. */
@Entity
@Table(name = "claim_evidence", schema = "claims")
public class ClaimEvidence {

    @Id
    @UuidGenerator
    @Column(name = "claim_evidence_id")
    private UUID claimEvidenceId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "claim_id", nullable = false)
    private UUID claimId;

    @Column(name = "document_ref", nullable = false)
    private String documentRef;

    @Column
    private String description;

    @Column(name = "uploaded_by", nullable = false)
    private String uploadedBy;

    /** Who {@link #uploadedBy} was, in words, as their token said at upload. Null on rows
     *  attached before V8. */
    @Column(name = "uploaded_by_name")
    private String uploadedByName;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt = Instant.now();

    protected ClaimEvidence() {}

    public ClaimEvidence(UUID tenantId, UUID claimId, String documentRef, String description, String uploadedBy,
                         String uploadedByName) {
        this.tenantId = tenantId;
        this.claimId = claimId;
        this.documentRef = documentRef;
        this.description = description;
        this.uploadedBy = uploadedBy;
        this.uploadedByName = uploadedByName;
    }

    public UUID getClaimEvidenceId() { return claimEvidenceId; }
    public UUID getTenantId() { return tenantId; }
    public UUID getClaimId() { return claimId; }
    public String getDocumentRef() { return documentRef; }
    public String getDescription() { return description; }
    public String getUploadedBy() { return uploadedBy; }
    public String getUploadedByName() { return uploadedByName; }
    public Instant getUploadedAt() { return uploadedAt; }
}
