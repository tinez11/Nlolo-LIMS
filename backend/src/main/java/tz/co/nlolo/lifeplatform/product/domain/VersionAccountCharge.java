package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.*;

import java.io.Serializable;
import java.util.UUID;

/** An Account charge a version takes (product V34), in the order it was chosen. */
@Entity
@Table(name = "version_account_charge", schema = "product")
@IdClass(VersionAccountCharge.Key.class)
public class VersionAccountCharge {

    public static class Key implements Serializable {
        private UUID productVersionId;
        private UUID chargeId;

        public Key() {}

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && java.util.Objects.equals(productVersionId, k.productVersionId)
                && java.util.Objects.equals(chargeId, k.chargeId);
        }

        @Override
        public int hashCode() { return java.util.Objects.hash(productVersionId, chargeId); }
    }

    @Id @Column(name = "product_version_id") private UUID productVersionId;
    @Id @Column(name = "charge_id") private UUID chargeId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(nullable = false) private int position;

    protected VersionAccountCharge() {}

    public VersionAccountCharge(UUID tenantId, UUID productVersionId, UUID chargeId, int position) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.chargeId = chargeId;
        this.position = position;
    }

    public UUID getChargeId() { return chargeId; }
}
