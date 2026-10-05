package tz.co.nlolo.lifeplatform.unitlinked.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One version of a policy's premium split (unitlinked V3, spec §4): the funds each premium is divided across, from
 * {@code effectiveFrom}. Never changed once written; a redirection is a new row. Every premium is split by the row in
 * force at the instant it was RECEIVED, so a premium already waiting keeps the split it arrived under.
 */
@Entity
@Table(name = "premium_split", schema = "unitlinked")
public class PremiumSplit {

    /** One fund's whole-percent share. */
    @Embeddable
    public static class Share {
        @Column(name = "fund_id", nullable = false) private UUID fundId;
        @Column(name = "percent", nullable = false) private int percent;

        protected Share() {}

        public Share(UUID fundId, int percent) {
            this.fundId = fundId;
            this.percent = percent;
        }

        public UUID getFundId() { return fundId; }
        public int getPercent() { return percent; }
    }

    @Id @Column(name = "split_id") private UUID splitId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(name = "effective_from", nullable = false) private Instant effectiveFrom;
    @Column(name = "recorded_by", nullable = false) private String recordedBy;
    @Column(name = "recorded_at", nullable = false) private Instant recordedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "premium_split_fund", schema = "unitlinked", joinColumns = @JoinColumn(name = "split_id"))
    private List<Share> shares = new ArrayList<>();

    protected PremiumSplit() {}

    public PremiumSplit(UUID tenantId, String policyNumber, Instant effectiveFrom, String recordedBy, Instant recordedAt,
                        List<Share> shares) {
        this.splitId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.effectiveFrom = effectiveFrom;
        this.recordedBy = recordedBy;
        this.recordedAt = recordedAt;
        this.shares = new ArrayList<>(shares);
    }

    public UUID getSplitId() { return splitId; }
    public String getPolicyNumber() { return policyNumber; }
    public Instant getEffectiveFrom() { return effectiveFrom; }
    public String getRecordedBy() { return recordedBy; }
    public Instant getRecordedAt() { return recordedAt; }
    public List<Share> getShares() { return List.copyOf(shares); }
}
