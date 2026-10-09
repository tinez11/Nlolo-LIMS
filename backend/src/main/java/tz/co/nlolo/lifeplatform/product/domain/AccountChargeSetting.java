package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/** The organisation's rule on whether a charge may take an account below its product's minimum balance (V32). */
@Entity
@Table(name = "account_charge_setting", schema = "product")
public class AccountChargeSetting {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "may_go_below_minimum", nullable = false)
    private boolean mayGoBelowMinimum;

    @Column(name = "updated_by", nullable = false)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected AccountChargeSetting() {}

    public AccountChargeSetting(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public void set(boolean mayGoBelowMinimum, String updatedBy) {
        this.mayGoBelowMinimum = mayGoBelowMinimum;
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
    }

    public boolean isMayGoBelowMinimum() { return mayGoBelowMinimum; }
}
