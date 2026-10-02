package tz.co.nlolo.lifeplatform.accumulation.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.accumulation.api.AccumulationStateException;
import tz.co.nlolo.lifeplatform.accumulation.api.RateDeclarationStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A declared rate for a product, on top of each version's guarantee. Two people, like every price. */
@Entity
@Table(name = "rate_declaration", schema = "accumulation")
public class RateDeclaration {
    @Id @UuidGenerator @Column(name = "declaration_id") private UUID declarationId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "product_id", nullable = false) private UUID productId;
    @Column(name = "rate_percent", nullable = false) private BigDecimal ratePercent;
    @Column(name = "effective_from", nullable = false) private LocalDate effectiveFrom;
    @Column(nullable = false) private String status = RateDeclarationStatus.PROPOSED.name();
    @Column(name = "proposed_by", nullable = false) private String proposedBy;
    @Column(name = "proposed_at", nullable = false) private Instant proposedAt = Instant.now();
    @Column(name = "approved_by") private String approvedBy;
    @Column(name = "approved_at") private Instant approvedAt;
    @Version private long version;

    protected RateDeclaration() {}

    public RateDeclaration(UUID tenantId, UUID productId, BigDecimal ratePercent, LocalDate effectiveFrom, String proposedBy) {
        this.tenantId = tenantId;
        this.productId = productId;
        this.ratePercent = ratePercent;
        this.effectiveFrom = effectiveFrom;
        this.proposedBy = proposedBy;
    }

    public RateDeclarationStatus status() { return RateDeclarationStatus.valueOf(status); }

    public void approve(String by) {
        requireProposed();
        if (by.equals(proposedBy)) {
            throw new AccumulationStateException(
                "A declared rate must be approved by someone other than the person who proposed it");
        }
        this.status = RateDeclarationStatus.APPROVED.name();
        this.approvedBy = by;
        this.approvedAt = Instant.now();
    }

    /** Only a proposal can be withdrawn: an approved rate may already have earned interest. */
    public void withdraw() {
        requireProposed();
        this.status = RateDeclarationStatus.WITHDRAWN.name();
    }

    private void requireProposed() {
        if (status() != RateDeclarationStatus.PROPOSED) {
            throw new AccumulationStateException("This rate declaration is " + status().name().toLowerCase()
                + ", not awaiting approval");
        }
    }

    public UUID getDeclarationId() { return declarationId; }
    public UUID getProductId() { return productId; }
    public BigDecimal getRatePercent() { return ratePercent; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public String getProposedBy() { return proposedBy; }
    public Instant getProposedAt() { return proposedAt; }
    public String getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
}
