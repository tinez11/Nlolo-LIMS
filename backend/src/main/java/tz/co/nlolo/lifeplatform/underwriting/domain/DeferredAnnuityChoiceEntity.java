package tz.co.nlolo.lifeplatform.underwriting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import tz.co.nlolo.lifeplatform.underwriting.api.DeferredAnnuityChoice;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A deferred annuity case's choice (underwriting V15). Changeable until the case is decided. */
@Entity
@Table(name = "deferred_annuity_choice", schema = "underwriting")
public class DeferredAnnuityChoiceEntity {
    @Id @Column(name = "case_id") private UUID caseId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "retirement_age", nullable = false) private int retirementAge;
    @Column(name = "recorded_by", nullable = false) private String recordedBy;
    @Column(name = "recorded_at", nullable = false) private Instant recordedAt = Instant.now();
    @Column(name = "age_evidence_confirmed_by") private String ageEvidenceConfirmedBy;
    @Column(name = "age_evidence_confirmed_at") private Instant ageEvidenceConfirmedAt;
    @Column(name = "confirmed_date_of_birth") private LocalDate confirmedDateOfBirth;
    @Column(name = "confirmed_sex") private String confirmedSex;

    protected DeferredAnnuityChoiceEntity() {}

    public DeferredAnnuityChoiceEntity(UUID tenantId, UUID caseId) {
        this.tenantId = tenantId;
        this.caseId = caseId;
    }

    public void choose(int retirementAge, String recordedBy) {
        this.retirementAge = retirementAge;
        this.recordedBy = recordedBy;
        this.recordedAt = Instant.now();
    }

    /** At acceptance: who saw proof of age, and the date of birth and sex the party record then held. */
    public void confirmAgeEvidence(String confirmedBy, LocalDate dateOfBirth, String sex) {
        this.ageEvidenceConfirmedBy = confirmedBy;
        this.ageEvidenceConfirmedAt = Instant.now();
        this.confirmedDateOfBirth = dateOfBirth;
        this.confirmedSex = sex;
    }

    public int getRetirementAge() {
        return retirementAge;
    }

    /** {@code recordedDateOfBirth}: the party's, used until acceptance fixes the confirmed one. */
    public DeferredAnnuityChoice toChoice(LocalDate recordedDateOfBirth) {
        LocalDate dob = confirmedDateOfBirth != null ? confirmedDateOfBirth : recordedDateOfBirth;
        return new DeferredAnnuityChoice(retirementAge, dob != null ? dob.plusYears(retirementAge) : null,
            ageEvidenceConfirmedBy, ageEvidenceConfirmedAt, confirmedDateOfBirth, confirmedSex);
    }
}
