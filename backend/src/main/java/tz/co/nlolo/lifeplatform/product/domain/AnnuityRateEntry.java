package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.product.api.AnnuityRateRow;

import java.math.BigDecimal;
import java.util.UUID;

/** One cell of an annuity form's grid (V22). */
@Entity
@Table(name = "annuity_rate_row", schema = "product")
public class AnnuityRateEntry {
    @Id @UuidGenerator @Column(name = "annuity_rate_row_id") private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "annuity_form_id", nullable = false) private UUID annuityFormId;
    @Column private String sex;
    @Column(nullable = false) private int age;
    @Column(name = "age_difference_from") private Integer ageDifferenceFrom;
    @Column(name = "age_difference_to") private Integer ageDifferenceTo;
    @Column(name = "annual_rate_per_mille", nullable = false) private BigDecimal annualRatePerMille;

    protected AnnuityRateEntry() {}

    public AnnuityRateEntry(UUID tenantId, UUID annuityFormId, AnnuityRateRow row) {
        this.tenantId = tenantId;
        this.annuityFormId = annuityFormId;
        this.sex = row.sex();
        this.age = row.age();
        this.ageDifferenceFrom = row.ageDifferenceFrom();
        this.ageDifferenceTo = row.ageDifferenceTo();
        this.annualRatePerMille = row.annualRatePerMille();
    }

    public AnnuityRateRow toRow() {
        return new AnnuityRateRow(sex, age, ageDifferenceFrom, ageDifferenceTo, annualRatePerMille);
    }
}
