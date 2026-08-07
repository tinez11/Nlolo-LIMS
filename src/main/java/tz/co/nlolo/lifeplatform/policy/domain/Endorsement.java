package tz.co.nlolo.lifeplatform.policy.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "endorsement", schema = "policy")
public class Endorsement {

    @Id
    @Column(name = "endorsement_id")
    private UUID endorsementId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "policy_number", nullable = false)
    private String policyNumber;

    @Column(name = "endorsement_type", nullable = false)
    private String endorsementType;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "changes", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> changes;

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Endorsement() {}

    public Endorsement(UUID tenantId, String policyNumber, String endorsementType, LocalDate effectiveDate,
                        Map<String, Object> changes, String approvedBy) {
        this.endorsementId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.endorsementType = endorsementType;
        this.effectiveDate = effectiveDate;
        this.changes = changes;
        this.approvedBy = approvedBy;
    }

    public UUID getEndorsementId() { return endorsementId; }
    public String getPolicyNumber() { return policyNumber; }
    public String getEndorsementType() { return endorsementType; }
    public LocalDate getEffectiveDate() { return effectiveDate; }
    public Map<String, Object> getChanges() { return changes; }
}
