package tz.co.nlolo.lifeplatform.claims.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A funeral plan's claim: which covered life died, and whether by accident (claims V10). 1:1 with the
 * claim, written in the registration's own transaction; read only once the policy says FUNERAL.
 */
@Entity
@Table(name = "funeral_claim", schema = "claims")
public class FuneralClaim {
    @Id @Column(name = "claim_id") private UUID claimId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "covered_life_id", nullable = false) private UUID coveredLifeId;
    @Column(nullable = false) private boolean accidental;
    @Column(name = "accidental_recorded_by") private String accidentalRecordedBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

    protected FuneralClaim() {}

    public FuneralClaim(UUID tenantId, UUID claimId, UUID coveredLifeId, boolean accidental, String recordedBy) {
        this.tenantId = tenantId;
        this.claimId = claimId;
        this.coveredLifeId = coveredLifeId;
        this.accidental = accidental;
        this.accidentalRecordedBy = accidental ? recordedBy : null;
    }

    public void recordAccidental(boolean accidental, String recordedBy) {
        this.accidental = accidental;
        this.accidentalRecordedBy = recordedBy;
    }

    public UUID getClaimId() { return claimId; }
    public UUID getCoveredLifeId() { return coveredLifeId; }
    public boolean isAccidental() { return accidental; }
}
