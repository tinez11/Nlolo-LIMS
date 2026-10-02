package tz.co.nlolo.lifeplatform.accumulation.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** The record of money arriving from another scheme, with what it came from. Written once. */
@Entity
@Immutable
@Table(name = "transfer_in", schema = "accumulation")
public class TransferIn {
    @Id @UuidGenerator @Column(name = "transfer_id") private UUID transferId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(nullable = false) private BigDecimal amount;
    @Column(nullable = false) private String currency;
    @Column(name = "source_scheme", nullable = false) private String sourceScheme;
    @Column(name = "document_ref") private String documentRef;
    @Column(name = "recorded_by", nullable = false) private String recordedBy;
    @Column(name = "recorded_at", nullable = false) private Instant recordedAt = Instant.now();

    protected TransferIn() {}

    public TransferIn(UUID tenantId, String policyNumber, BigDecimal amount, String currency, String sourceScheme,
                      String documentRef, String recordedBy) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.amount = amount;
        this.currency = currency;
        this.sourceScheme = sourceScheme;
        this.documentRef = documentRef;
        this.recordedBy = recordedBy;
    }

    public UUID getTransferId() { return transferId; }
    public String getPolicyNumber() { return policyNumber; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getSourceScheme() { return sourceScheme; }
    public String getDocumentRef() { return documentRef; }
    public String getRecordedBy() { return recordedBy; }
    public Instant getRecordedAt() { return recordedAt; }
}
