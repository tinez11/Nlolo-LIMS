package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.product.api.AnnuityFrequencyFactor;

import java.math.BigDecimal;
import java.util.UUID;

/** A payment frequency an annuity version offers, and its factor (V22). */
@Entity
@Table(name = "annuity_frequency", schema = "product")
public class AnnuityFrequencyEntry {
    @Id @UuidGenerator @Column(name = "annuity_frequency_id") private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "product_version_id", nullable = false) private UUID productVersionId;
    @Column(nullable = false) private String frequency;
    @Column(nullable = false) private BigDecimal factor;

    protected AnnuityFrequencyEntry() {}

    public AnnuityFrequencyEntry(UUID tenantId, UUID productVersionId, AnnuityFrequencyFactor f) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.frequency = f.frequency();
        this.factor = f.factor();
    }

    public AnnuityFrequencyFactor toFactor() {
        return new AnnuityFrequencyFactor(frequency, factor);
    }
}
