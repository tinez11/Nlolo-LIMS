package tz.co.nlolo.lifeplatform.claims.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * A document claims staff asked the claimant for (2026-10-08, claims V11): what, why, and whether it came. The claimant
 * uploads against it from the portal, which links the evidence and marks it received.
 */
@Entity
@Table(name = "claim_document_request", schema = "claims")
public class ClaimDocumentRequest {

    public enum Status { OPEN, RECEIVED, WITHDRAWN }

    @Id @Column(name = "request_id") private UUID requestId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "claim_id", nullable = false) private UUID claimId;
    @Column(nullable = false) private String document;
    @Column private String reason;
    @Column(name = "requested_by", nullable = false) private String requestedBy;
    @Column(name = "requested_by_name") private String requestedByName;
    @Column(name = "requested_at", nullable = false) private Instant requestedAt;
    @Column(nullable = false) private String status;
    @Column(name = "claim_evidence_id") private UUID claimEvidenceId;
    @Column(name = "received_at") private Instant receivedAt;
    @Version private long version;

    protected ClaimDocumentRequest() {}

    public ClaimDocumentRequest(UUID tenantId, UUID claimId, String document, String reason, String requestedBy,
                                String requestedByName) {
        this.requestId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.claimId = claimId;
        this.document = document;
        this.reason = reason;
        this.requestedBy = requestedBy;
        this.requestedByName = requestedByName;
        this.requestedAt = Instant.now();
        this.status = Status.OPEN.name();
    }

    /** The claimant uploaded the document asked for. */
    public void received(UUID evidenceId) {
        this.status = Status.RECEIVED.name();
        this.claimEvidenceId = evidenceId;
        this.receivedAt = Instant.now();
    }

    /** Staff no longer need it. */
    public void withdrawn() {
        this.status = Status.WITHDRAWN.name();
    }

    public UUID getRequestId() { return requestId; }
    public UUID getClaimId() { return claimId; }
    public String getDocument() { return document; }
    public String getReason() { return reason; }
    public String getRequestedByName() { return requestedByName; }
    public Instant getRequestedAt() { return requestedAt; }
    public Status getStatus() { return Status.valueOf(status); }
    public UUID getClaimEvidenceId() { return claimEvidenceId; }
    public Instant getReceivedAt() { return receivedAt; }
}
