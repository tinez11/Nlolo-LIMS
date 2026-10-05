package tz.co.nlolo.lifeplatform.unitlinked.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A top-up (U2, spec §4): extra money the customer pays in, collected through payment under UL_TOP_UP. Allocated at the
 * version's own top-up percent when the money is RECEIVED, by its own split or the one in force; refunded whole when
 * the policy is frozen or has ended by then. The sum assured never changes.
 */
@Entity(name = "UnitLinkedTopUp")
@Table(name = "top_up", schema = "unitlinked")
public class TopUp {

    @Id @Column(name = "top_up_id") private UUID topUpId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(name = "amount", nullable = false) private BigDecimal amount;
    @Column(name = "currency", nullable = false) private String currency;
    @Column(name = "payer_ref", nullable = false) private String payerRef;
    @Column(name = "status", nullable = false) private String status;
    @Column(name = "requested_by", nullable = false) private String requestedBy;
    @Column(name = "requested_at", nullable = false) private Instant requestedAt;
    @Column(name = "received_at") private Instant receivedAt;
    @Version @Column(name = "version", nullable = false) private long version;

    /** Its own split, when one was given: the same embeddable as the premium split. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "top_up_fund", schema = "unitlinked", joinColumns = @JoinColumn(name = "top_up_id"))
    private List<PremiumSplit.Share> split = new ArrayList<>();

    protected TopUp() {}

    public TopUp(UUID tenantId, String policyNumber, BigDecimal amount, String currency, String payerRef,
                 List<PremiumSplit.Share> split, String requestedBy, Instant requestedAt) {
        this.topUpId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.amount = amount;
        this.currency = currency;
        this.payerRef = payerRef;
        this.split = new ArrayList<>(split);
        this.requestedBy = requestedBy;
        this.requestedAt = requestedAt;
        this.status = "REQUESTED";
    }

    /** Whether a confirmation still has to be acted on -- false on a redelivery. */
    public boolean awaitingMoney() { return "REQUESTED".equals(status); }

    public void received(Instant at) {
        this.status = "RECEIVED";
        this.receivedAt = at;
    }

    public void refunded(Instant at) {
        this.status = "REFUNDED";
        this.receivedAt = at;
    }

    public void failed() {
        if (awaitingMoney()) {
            this.status = "FAILED";
        }
    }

    public UUID getTopUpId() { return topUpId; }
    public UUID getTenantId() { return tenantId; }
    public String getPolicyNumber() { return policyNumber; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getPayerRef() { return payerRef; }
    public String getStatus() { return status; }
    public String getRequestedBy() { return requestedBy; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getReceivedAt() { return receivedAt; }
    public List<PremiumSplit.Share> getSplit() { return List.copyOf(split); }
}
