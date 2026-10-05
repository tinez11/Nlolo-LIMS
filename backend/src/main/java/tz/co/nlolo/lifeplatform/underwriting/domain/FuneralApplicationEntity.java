package tz.co.nlolo.lifeplatform.underwriting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A funeral case's chosen plan (underwriting V16). Changeable until the case is decided. */
@Entity
@Table(name = "funeral_application", schema = "underwriting")
public class FuneralApplicationEntity {
    @Id @Column(name = "case_id") private UUID caseId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "plan_code", nullable = false) private String planCode;
    @Column(name = "recorded_by", nullable = false) private String recordedBy;
    @Column(name = "recorded_at", nullable = false) private Instant recordedAt = Instant.now();

    protected FuneralApplicationEntity() {}

    public FuneralApplicationEntity(UUID tenantId, UUID caseId) {
        this.tenantId = tenantId;
        this.caseId = caseId;
    }

    public void choose(String planCode, String recordedBy) {
        this.planCode = planCode;
        this.recordedBy = recordedBy;
        this.recordedAt = Instant.now();
    }

    public UUID getCaseId() { return caseId; }
    public String getPlanCode() { return planCode; }
}
