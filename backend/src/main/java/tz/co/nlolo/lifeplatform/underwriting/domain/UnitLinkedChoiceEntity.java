package tz.co.nlolo.lifeplatform.underwriting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A unit-linked case's chosen premium, frequency and sum assured (underwriting V17). Changeable until decided. */
@Entity
@Table(name = "unit_linked_choice", schema = "underwriting")
public class UnitLinkedChoiceEntity {
    @Id @Column(name = "case_id") private UUID caseId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "premium_amount", nullable = false) private BigDecimal premiumAmount;
    @Column(name = "premium_frequency", nullable = false) private String premiumFrequency;
    @Column(name = "sum_assured", nullable = false) private BigDecimal sumAssured;
    @Column(name = "recorded_by", nullable = false) private String recordedBy;
    @Column(name = "recorded_at", nullable = false) private Instant recordedAt = Instant.now();

    protected UnitLinkedChoiceEntity() {}

    public UnitLinkedChoiceEntity(UUID tenantId, UUID caseId) {
        this.tenantId = tenantId;
        this.caseId = caseId;
    }

    public void choose(BigDecimal premiumAmount, String premiumFrequency, BigDecimal sumAssured, String recordedBy) {
        this.premiumAmount = premiumAmount;
        this.premiumFrequency = premiumFrequency;
        this.sumAssured = sumAssured;
        this.recordedBy = recordedBy;
        this.recordedAt = Instant.now();
    }

    public UUID getCaseId() { return caseId; }
    public BigDecimal getPremiumAmount() { return premiumAmount; }
    public String getPremiumFrequency() { return premiumFrequency; }
    public BigDecimal getSumAssured() { return sumAssured; }
}
