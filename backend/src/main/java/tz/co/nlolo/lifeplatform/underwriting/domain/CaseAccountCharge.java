package tz.co.nlolo.lifeplatform.underwriting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.UUID;

/** One account charge chosen on a savings case (V21). */
@Entity
@Table(name = "case_account_charge", schema = "underwriting")
public class CaseAccountCharge {

    @Embeddable
    public record Key(@Column(name = "case_id") UUID caseId, @Column(name = "charge_id") UUID chargeId)
        implements Serializable {}

    @EmbeddedId
    private Key key;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    protected CaseAccountCharge() {}

    public CaseAccountCharge(UUID caseId, UUID chargeId, UUID tenantId) {
        this.key = new Key(caseId, chargeId);
        this.tenantId = tenantId;
    }

    public UUID getChargeId() { return key.chargeId(); }
}
