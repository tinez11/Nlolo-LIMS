package tz.co.nlolo.lifeplatform.bonus.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** One participating policy, and the RUNNING HEAD of its ledger. @Version serialises entries. */
@Entity
@Table(name = "participant", schema = "bonus")
public class Participant {
    @Id @Column(name = "policy_number") private String policyNumber;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "product_id", nullable = false) private UUID productId;
    @Column(name = "product_version_id", nullable = false) private UUID productVersionId;
    @Column(nullable = false) private String currency;
    @Column(name = "issued_on", nullable = false) private LocalDate issuedOn;
    @Column(name = "last_seq", nullable = false) private int lastSeq;
    @Column(name = "attached_total", nullable = false) private BigDecimal attachedTotal = BigDecimal.ZERO;
    @Version private long version;

    protected Participant() {}

    public Participant(UUID tenantId, String policyNumber, UUID productId, UUID productVersionId, String currency,
                       LocalDate issuedOn) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.productId = productId;
        this.productVersionId = productVersionId;
        this.currency = currency;
        this.issuedOn = issuedOn;
    }

    /** Move the head by one entry; returns that entry's seq. The trigger checks the arithmetic. */
    public int advance(BigDecimal amount) {
        this.attachedTotal = attachedTotal.add(amount);
        return ++lastSeq;
    }

    public String getPolicyNumber() { return policyNumber; }
    public UUID getTenantId() { return tenantId; }
    public UUID getProductId() { return productId; }
    public UUID getProductVersionId() { return productVersionId; }
    public String getCurrency() { return currency; }
    public LocalDate getIssuedOn() { return issuedOn; }
    public int getLastSeq() { return lastSeq; }
    public BigDecimal getAttachedTotal() { return attachedTotal; }
}
