package tz.co.nlolo.lifeplatform.bonus.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.bonus.api.BonusStateException;
import tz.co.nlolo.lifeplatform.bonus.api.DeclarationStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A bonus declared on a product as at a valuation date. Two people, like every price. */
@Entity
@Table(name = "declaration", schema = "bonus")
public class Declaration {
    @Id @UuidGenerator @Column(name = "declaration_id") private UUID declarationId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "product_id", nullable = false) private UUID productId;
    @Column(name = "valuation_date", nullable = false) private LocalDate valuationDate;
    @Column(name = "reversionary_rate_percent", nullable = false) private BigDecimal reversionaryRatePercent;
    @Column(name = "terminal_rate_percent", nullable = false) private BigDecimal terminalRatePercent;
    @Column(nullable = false) private String status = DeclarationStatus.PROPOSED.name();
    @Column(name = "proposed_by", nullable = false) private String proposedBy;
    @Column(name = "proposed_at", nullable = false) private Instant proposedAt = Instant.now();
    @Column(name = "approved_by") private String approvedBy;
    @Column(name = "approved_at") private Instant approvedAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Version private long version;

    protected Declaration() {}

    public Declaration(UUID tenantId, UUID productId, LocalDate valuationDate, BigDecimal reversionaryRatePercent,
                       BigDecimal terminalRatePercent, String proposedBy) {
        this.tenantId = tenantId;
        this.productId = productId;
        this.valuationDate = valuationDate;
        this.reversionaryRatePercent = reversionaryRatePercent;
        this.terminalRatePercent = terminalRatePercent;
        this.proposedBy = proposedBy;
    }

    public DeclarationStatus status() { return DeclarationStatus.valueOf(status); }

    public void approve(String by) {
        requireProposed();
        if (by.equals(proposedBy)) {
            throw new BonusStateException("A bonus declaration must be approved by someone other than the person who proposed it");
        }
        this.status = DeclarationStatus.APPROVED.name();
        this.approvedBy = by;
        this.approvedAt = Instant.now();
    }

    /** Only a proposal: an approved declaration may already have attached to a policy. */
    public void withdraw() {
        requireProposed();
        this.status = DeclarationStatus.WITHDRAWN.name();
    }

    public void complete() { this.completedAt = Instant.now(); }

    private void requireProposed() {
        if (status() != DeclarationStatus.PROPOSED) {
            throw new BonusStateException("This bonus declaration is " + status().name().toLowerCase() + ", not awaiting approval");
        }
    }

    public UUID getDeclarationId() { return declarationId; }
    public UUID getProductId() { return productId; }
    public LocalDate getValuationDate() { return valuationDate; }
    public BigDecimal getReversionaryRatePercent() { return reversionaryRatePercent; }
    public BigDecimal getTerminalRatePercent() { return terminalRatePercent; }
    public String getProposedBy() { return proposedBy; }
    public Instant getProposedAt() { return proposedAt; }
    public String getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
