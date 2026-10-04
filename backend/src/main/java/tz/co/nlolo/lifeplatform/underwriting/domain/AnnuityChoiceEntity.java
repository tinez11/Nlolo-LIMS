package tz.co.nlolo.lifeplatform.underwriting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import tz.co.nlolo.lifeplatform.underwriting.api.AnnuityChoice;

import java.time.Instant;
import java.util.UUID;

/** An annuity case's choice (underwriting V14). Changeable until the case is decided. */
@Entity
@Table(name = "annuity_choice", schema = "underwriting")
public class AnnuityChoiceEntity {
    @Id @Column(name = "case_id") private UUID caseId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "form_code", nullable = false) private String formCode;
    @Column(nullable = false) private String frequency;
    @Column(name = "joint_life_party_id") private UUID jointLifePartyId;
    @Column(name = "recorded_by", nullable = false) private String recordedBy;
    @Column(name = "recorded_at", nullable = false) private Instant recordedAt = Instant.now();
    @Column(name = "age_evidence_confirmed_by") private String ageEvidenceConfirmedBy;
    @Column(name = "age_evidence_confirmed_at") private Instant ageEvidenceConfirmedAt;

    protected AnnuityChoiceEntity() {}

    public AnnuityChoiceEntity(UUID tenantId, UUID caseId) {
        this.tenantId = tenantId;
        this.caseId = caseId;
    }

    public void choose(String formCode, String frequency, UUID jointLifePartyId, String recordedBy) {
        this.formCode = formCode;
        this.frequency = frequency;
        this.jointLifePartyId = jointLifePartyId;
        this.recordedBy = recordedBy;
        this.recordedAt = Instant.now();
    }

    public void confirmAgeEvidence(String confirmedBy) {
        this.ageEvidenceConfirmedBy = confirmedBy;
        this.ageEvidenceConfirmedAt = Instant.now();
    }

    public AnnuityChoice toChoice() {
        return new AnnuityChoice(formCode, frequency, jointLifePartyId, ageEvidenceConfirmedBy, ageEvidenceConfirmedAt);
    }
}
