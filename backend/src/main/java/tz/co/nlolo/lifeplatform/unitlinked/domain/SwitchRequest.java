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
import jakarta.persistence.Version;
import tz.co.nlolo.lifeplatform.unitlinked.api.UnitLinkedStateException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A fund switch (U2, spec §2): which funds to move out of and by what share of their units, and the split the
 * proceeds buy into. Bound to ONE valuation date for both legs -- the latest of the involved funds' bindings -- and
 * executed at the first date on or after it on which every involved fund has an approved price.
 */
@Entity
@Table(name = "switch_request", schema = "unitlinked")
public class SwitchRequest {

    /** One leg: OUT sells {@code percent}% of the fund's units; IN buys {@code percent}% of the net proceeds. */
    @Embeddable
    public static class Leg {
        @Column(name = "fund_id", nullable = false) private UUID fundId;
        @Column(name = "side", nullable = false) private String side;
        @Column(name = "percent", nullable = false) private int percent;

        protected Leg() {}

        public Leg(UUID fundId, String side, int percent) {
            this.fundId = fundId;
            this.side = side;
            this.percent = percent;
        }

        public UUID getFundId() { return fundId; }
        public String getSide() { return side; }
        public int getPercent() { return percent; }
        public boolean isOut() { return "OUT".equals(side); }
    }

    @Id @Column(name = "switch_id") private UUID switchId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(name = "requested_at", nullable = false) private Instant requestedAt;
    @Column(name = "requested_by", nullable = false) private String requestedBy;
    @Column(name = "bound_date", nullable = false) private LocalDate boundDate;
    @Column(name = "status", nullable = false) private String status;
    @Column(name = "executed_on") private LocalDate executedOn;
    @Column(name = "fee", nullable = false) private BigDecimal fee;
    @Version @Column(name = "version", nullable = false) private long version;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "switch_leg", schema = "unitlinked", joinColumns = @JoinColumn(name = "switch_id"))
    private List<Leg> legs = new ArrayList<>();

    protected SwitchRequest() {}

    public SwitchRequest(UUID tenantId, String policyNumber, Instant requestedAt, String requestedBy, LocalDate boundDate,
                         List<Leg> legs) {
        this.switchId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.requestedAt = requestedAt;
        this.requestedBy = requestedBy;
        this.boundDate = boundDate;
        this.status = "WAITING";
        this.fee = BigDecimal.ZERO;
        this.legs = new ArrayList<>(legs);
    }

    public void executed(LocalDate on, BigDecimal fee) {
        requireWaiting();
        this.status = "EXECUTED";
        this.executedOn = on;
        this.fee = fee;
    }

    public void cancel() {
        requireWaiting();
        this.status = "CANCELLED";
    }

    private void requireWaiting() {
        if (!"WAITING".equals(status)) {
            throw new UnitLinkedStateException("Switch " + switchId + " is " + status + "; only a waiting switch moves");
        }
    }

    public UUID getSwitchId() { return switchId; }
    public UUID getTenantId() { return tenantId; }
    public String getPolicyNumber() { return policyNumber; }
    public Instant getRequestedAt() { return requestedAt; }
    public String getRequestedBy() { return requestedBy; }
    public LocalDate getBoundDate() { return boundDate; }
    public String getStatus() { return status; }
    public LocalDate getExecutedOn() { return executedOn; }
    public BigDecimal getFee() { return fee; }
    public List<Leg> getLegs() { return List.copyOf(legs); }
}
