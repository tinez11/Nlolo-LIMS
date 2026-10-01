package tz.co.nlolo.lifeplatform.benefitpayout.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line of what the insurer withheld from a free-look refund, and the evidence for it.
 *
 * <p>A separate row per item rather than a single total, because the customer is told what each
 * one was for. {@code documentId} points at the invoice or report that justifies it.
 */
@Entity
@Table(name = "free_look_deduction", schema = "benefitpayout")
public class FreeLookDeduction {

    @Id
    @UuidGenerator
    @Column(name = "deduction_id")
    private UUID deductionId;

    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "cancellation_id", nullable = false) private UUID cancellationId;
    @Column(nullable = false) private String description;
    @Column(nullable = false) private BigDecimal amount;
    @Column(name = "document_id") private UUID documentId;

    protected FreeLookDeduction() {}

    public FreeLookDeduction(UUID tenantId, UUID cancellationId, String description, BigDecimal amount,
                             UUID documentId) {
        this.tenantId = tenantId;
        this.cancellationId = cancellationId;
        this.description = description;
        this.amount = amount;
        this.documentId = documentId;
    }

    public String getDescription() { return description; }
    public BigDecimal getAmount() { return amount; }
    public UUID getDocumentId() { return documentId; }
}
