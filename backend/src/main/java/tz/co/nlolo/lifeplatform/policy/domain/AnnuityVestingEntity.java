package tz.co.nlolo.lifeplatform.policy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A deferred annuity's vesting on the policy's record (policy V34). Written once, never changed. */
@Entity
@Table(name = "annuity_vesting", schema = "policy")
public class AnnuityVestingEntity {
    @Id @Column(name = "policy_number") private String policyNumber;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "vested_on", nullable = false) private LocalDate vestedOn;
    @Column(name = "recorded_at", nullable = false) private Instant recordedAt = Instant.now();

    protected AnnuityVestingEntity() {}

    public AnnuityVestingEntity(UUID tenantId, String policyNumber, LocalDate vestedOn) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.vestedOn = vestedOn;
    }

    public LocalDate getVestedOn() {
        return vestedOn;
    }
}
