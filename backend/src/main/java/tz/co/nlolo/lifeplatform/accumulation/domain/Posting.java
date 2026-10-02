package tz.co.nlolo.lifeplatform.accumulation.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/** One source's postings, written once. {@code @Immutable}: Hibernate never issues an UPDATE for it. */
@Entity
@Immutable
@Table(name = "posting", schema = "accumulation")
public class Posting {
    @Id @UuidGenerator @Column(name = "posting_id") private UUID postingId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(name = "source_type", nullable = false) private String sourceType;
    @Column(name = "source_ref", nullable = false) private String sourceRef;
    @Column(name = "posted_at", nullable = false) private Instant postedAt = Instant.now();
    @Column(name = "created_by", nullable = false) private String createdBy;
    @Column(name = "approved_by") private String approvedBy;

    protected Posting() {}

    public Posting(UUID tenantId, String policyNumber, String sourceType, String sourceRef, String createdBy,
                   String approvedBy) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.sourceType = sourceType;
        this.sourceRef = sourceRef;
        this.createdBy = createdBy;
        this.approvedBy = approvedBy;
    }

    public UUID getPostingId() { return postingId; }
    public String getSourceType() { return sourceType; }
    public String getSourceRef() { return sourceRef; }
}
