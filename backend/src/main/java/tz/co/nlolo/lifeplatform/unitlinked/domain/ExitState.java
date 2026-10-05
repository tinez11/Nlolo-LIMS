package tz.co.nlolo.lifeplatform.unitlinked.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One exit's progress (unitlinked V2): a death, surrender, maturity, lapse or free-look -- the money returned
 * from orders cancelled, what its sales raised once priced, and whether it has been paid. A price correction reads
 * it to know whether a movement it re-runs funded a payout already PAID (spec §3).
 */
@Entity
@Table(name = "exit_state", schema = "unitlinked")
public class ExitState {

    public enum Status { OPEN, PRICED, AWAITING_PAYEE, PAID, REVERSED }

    @Id @Column(name = "exit_id") private UUID exitId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "source_type", nullable = false) private String sourceType;
    @Column(name = "source_ref", nullable = false) private String sourceRef;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(name = "purpose", nullable = false) private String purpose;
    @Column(name = "returned_money", nullable = false) private BigDecimal returnedMoney;
    @Column(name = "proceeds", nullable = false) private BigDecimal proceeds;
    // U2 (unitlinked V3): the surrender charge a surrender or a non-payment lapse took from its proceeds.
    @Column(name = "surrender_charge", nullable = false) private BigDecimal surrenderCharge = BigDecimal.ZERO.setScale(2);
    @Column(name = "status", nullable = false) private String status;
    @Column(name = "payee_ref") private String payeeRef;
    @Column(name = "completed_at") private Instant completedAt;
    @Version @Column(name = "version", nullable = false) private long version;

    protected ExitState() {}

    public ExitState(UUID tenantId, String policyNumber, String purpose, String sourceType, String sourceRef, String payeeRef) {
        this.exitId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.purpose = purpose;
        this.sourceType = sourceType;
        this.sourceRef = sourceRef;
        this.payeeRef = payeeRef;
        this.returnedMoney = BigDecimal.ZERO.setScale(2);
        this.proceeds = BigDecimal.ZERO.setScale(2);
        this.status = Status.OPEN.name();
    }

    public void addReturnedMoney(BigDecimal amount) { this.returnedMoney = returnedMoney.add(amount); }
    public void addProceeds(BigDecimal amount) { this.proceeds = proceeds.add(amount); }
    public void chargeSurrender(BigDecimal charge) { this.surrenderCharge = charge; }
    public void priced(Instant at) { this.status = Status.PRICED.name(); this.completedAt = at; }
    public void awaitingPayee() { this.status = Status.AWAITING_PAYEE.name(); }
    public void paid(Instant at) { this.status = Status.PAID.name(); this.completedAt = at; }
    public void reversed(Instant at) { this.status = Status.REVERSED.name(); this.completedAt = at; }
    public void payTo(String payeeRef) { this.payeeRef = payeeRef; }

    public UUID getExitId() { return exitId; }
    public UUID getTenantId() { return tenantId; }
    public String getSourceType() { return sourceType; }
    public String getSourceRef() { return sourceRef; }
    public String getPolicyNumber() { return policyNumber; }
    public String getPurpose() { return purpose; }
    public BigDecimal getReturnedMoney() { return returnedMoney; }
    public BigDecimal getProceeds() { return proceeds; }
    public BigDecimal getSurrenderCharge() { return surrenderCharge; }
    /** What the payee is paid: the units' proceeds and any premium returned, less the surrender charge. */
    public BigDecimal payable() { return proceeds.add(returnedMoney).subtract(surrenderCharge); }
    public Status getStatus() { return Status.valueOf(status); }
    public String getPayeeRef() { return payeeRef; }
    public Instant getCompletedAt() { return completedAt; }
}
