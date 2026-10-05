package tz.co.nlolo.lifeplatform.underwriting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import tz.co.nlolo.lifeplatform.underwriting.api.UnitLinkedChoice;

import java.util.UUID;

/** One fund's share of a unit-linked case's premiums (underwriting V17). */
@Entity
@Table(name = "unit_linked_choice_split", schema = "underwriting")
public class UnitLinkedChoiceSplitEntity {
    @Id @Column(name = "unit_linked_choice_split_id") private UUID unitLinkedChoiceSplitId = UUID.randomUUID();
    @Column(name = "case_id", nullable = false) private UUID caseId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "fund_code", nullable = false) private String fundCode;
    @Column(name = "percent", nullable = false) private int percent;

    protected UnitLinkedChoiceSplitEntity() {}

    public UnitLinkedChoiceSplitEntity(UUID tenantId, UUID caseId, UnitLinkedChoice.Split split) {
        this.tenantId = tenantId;
        this.caseId = caseId;
        this.fundCode = split.fundCode();
        this.percent = split.percent();
    }

    public UnitLinkedChoice.Split toSplit() {
        return new UnitLinkedChoice.Split(fundCode, percent);
    }
}
