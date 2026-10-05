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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A partial withdrawal (U2, spec §3): a GROSS amount, sold from named funds or pro rata, requested by one person and
 * approved by a second -- the sale binds at the approval instant. The customer is paid the proceeds less the surrender
 * charge; a price fall that leaves a fund short sells the whole fund and records the shortfall.
 */
@Entity(name = "UnitLinkedWithdrawalRequest")
@Table(name = "withdrawal_request", schema = "unitlinked")
public class WithdrawalRequest {

    /** A fund named on the request and the gross amount to sell from it. */
    @Embeddable
    public static class Named {
        @Column(name = "fund_id", nullable = false) private UUID fundId;
        @Column(name = "amount", nullable = false) private BigDecimal amount;

        protected Named() {}

        public Named(UUID fundId, BigDecimal amount) {
            this.fundId = fundId;
            this.amount = amount;
        }

        public UUID getFundId() { return fundId; }
        public BigDecimal getAmount() { return amount; }
    }

    @Id @Column(name = "withdrawal_id") private UUID withdrawalId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(name = "gross_amount", nullable = false) private BigDecimal grossAmount;
    @Column(name = "payee_ref", nullable = false) private String payeeRef;
    @Column(name = "status", nullable = false) private String status;
    @Column(name = "requested_by", nullable = false) private String requestedBy;
    @Column(name = "requested_at", nullable = false) private Instant requestedAt;
    @Column(name = "approved_by") private String approvedBy;
    @Column(name = "approved_at") private Instant approvedAt;
    @Column(name = "proceeds", nullable = false) private BigDecimal proceeds;
    @Column(name = "surrender_charge", nullable = false) private BigDecimal surrenderCharge;
    @Column(name = "shortfall", nullable = false) private BigDecimal shortfall;
    @Version @Column(name = "version", nullable = false) private long version;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "withdrawal_fund", schema = "unitlinked", joinColumns = @JoinColumn(name = "withdrawal_id"))
    private List<Named> named = new ArrayList<>();

    protected WithdrawalRequest() {}

    public WithdrawalRequest(UUID tenantId, String policyNumber, BigDecimal grossAmount, List<Named> named, String payeeRef,
                             String requestedBy, Instant requestedAt) {
        this.withdrawalId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.grossAmount = grossAmount;
        this.named = new ArrayList<>(named);
        this.payeeRef = payeeRef;
        this.requestedBy = requestedBy;
        this.requestedAt = requestedAt;
        this.status = "REQUESTED";
        this.proceeds = BigDecimal.ZERO;
        this.surrenderCharge = BigDecimal.ZERO;
        this.shortfall = BigDecimal.ZERO;
    }

    public void approve(String by, Instant at) {
        if (!"REQUESTED".equals(status)) {
            throw new UnitLinkedStateException("Withdrawal " + withdrawalId + " is " + status + ", not awaiting approval");
        }
        if (by == null || by.equals(requestedBy)) {
            throw new UnitLinkedStateException("A withdrawal must be approved by someone other than the person who requested"
                + " it (" + requestedBy + "); a second person approves");
        }
        this.status = "APPROVED";
        this.approvedBy = by;
        this.approvedAt = at;
    }

    /** One fund's sale priced: what it raised, and what it fell short of the amount asked of that fund. */
    public void sold(BigDecimal raised, BigDecimal shortOf) {
        this.proceeds = proceeds.add(raised);
        this.shortfall = shortfall.add(shortOf.max(BigDecimal.ZERO));
    }

    public void priced(BigDecimal charge) {
        this.surrenderCharge = charge;
        this.status = "PRICED";
    }

    public void paid() {
        this.status = "PAID";
    }

    public void cancel() {
        this.status = "CANCELLED";
    }

    public boolean isLive() { return "REQUESTED".equals(status) || "APPROVED".equals(status); }

    public UUID getWithdrawalId() { return withdrawalId; }
    public UUID getTenantId() { return tenantId; }
    public String getPolicyNumber() { return policyNumber; }
    public BigDecimal getGrossAmount() { return grossAmount; }
    public List<Named> getNamed() { return List.copyOf(named); }
    public String getPayeeRef() { return payeeRef; }
    public String getStatus() { return status; }
    public String getRequestedBy() { return requestedBy; }
    public Instant getRequestedAt() { return requestedAt; }
    public String getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public BigDecimal getProceeds() { return proceeds; }
    public BigDecimal getSurrenderCharge() { return surrenderCharge; }
    public BigDecimal getShortfall() { return shortfall; }
}
