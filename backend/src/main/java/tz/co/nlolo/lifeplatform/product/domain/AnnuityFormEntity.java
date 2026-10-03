package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.product.api.AnnuityForm;
import tz.co.nlolo.lifeplatform.product.api.AnnuityRateBasis;
import tz.co.nlolo.lifeplatform.product.api.AnnuityRateRow;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** One annuity form a version offers (V22). Its rates are {@link AnnuityRateEntry} rows. */
@Entity
@Table(name = "annuity_form", schema = "product")
public class AnnuityFormEntity {
    @Id @UuidGenerator @Column(name = "annuity_form_id") private UUID annuityFormId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "product_version_id", nullable = false) private UUID productVersionId;
    @Column(name = "form_code", nullable = false) private String formCode;
    @Column(name = "guarantee_years", nullable = false) private int guaranteeYears;
    @Column(nullable = false) private boolean joint;
    @Column(name = "survivor_percent") private BigDecimal survivorPercent;
    @Column(name = "escalation_percent", nullable = false) private BigDecimal escalationPercent;
    @Column(name = "capital_protected", nullable = false) private boolean capitalProtected;
    @Column(name = "rate_basis", nullable = false) private String rateBasis;

    protected AnnuityFormEntity() {}

    public AnnuityFormEntity(UUID tenantId, UUID productVersionId, AnnuityForm form) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.formCode = form.formCode();
        this.guaranteeYears = form.guaranteeYears();
        this.joint = form.joint();
        this.survivorPercent = form.survivorPercent();
        this.escalationPercent = form.escalationPercent();
        this.capitalProtected = form.capitalProtected();
        this.rateBasis = form.rateBasis().name();
    }

    public UUID getAnnuityFormId() { return annuityFormId; }

    public AnnuityForm toForm(List<AnnuityRateRow> rates) {
        return new AnnuityForm(formCode, guaranteeYears, joint, survivorPercent, escalationPercent, capitalProtected,
            AnnuityRateBasis.valueOf(rateBasis), rates);
    }
}
