package tz.co.nlolo.lifeplatform.underwriting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The quotation a customer agreed to (2026-10-20, underwriting V22): written when the agent confirms, never by quoting.
 *
 * <p>{@code inputs} and {@code quoted} are opaque here -- the omnichannel quotation owns their shape, exactly as a
 * disclosure set's questions are the product's. What this module owns is who it belongs to and whether a proposal was
 * opened from it.
 */
@Entity
@Table(name = "accepted_quotation", schema = "underwriting")
public class AcceptedQuotation {

    @Id
    @Column(name = "quotation_id")
    private UUID quotationId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** The identity-provider subject of the agent who confirmed it: the only caller who may read, discard or submit it. */
    @Column(name = "owner_subject", nullable = false)
    private String ownerSubject;

    @Column(name = "applicant_party_id")
    private UUID applicantPartyId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "product_version_id", nullable = false)
    private UUID productVersionId;

    /** JSON, bound as JSON -- see {@link MedicalDisclosure}'s note on why the type code is required. */
    @Column(name = "inputs", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String inputs;

    @Column(name = "quoted", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String quoted;

    @Column(name = "quoted_on", nullable = false)
    private LocalDate quotedOn;

    @Column(name = "valid_until", nullable = false)
    private LocalDate validUntil;

    @Column(name = "case_id")
    private UUID caseId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "submitted_at")
    private Instant submittedAt;

    protected AcceptedQuotation() {}

    public AcceptedQuotation(UUID tenantId, String ownerSubject, UUID applicantPartyId, UUID productId,
                             UUID productVersionId, String inputs, String quoted, LocalDate quotedOn,
                             LocalDate validUntil) {
        this.quotationId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.ownerSubject = ownerSubject;
        this.applicantPartyId = applicantPartyId;
        this.productId = productId;
        this.productVersionId = productVersionId;
        this.inputs = inputs;
        this.quoted = quoted;
        this.quotedOn = quotedOn;
        this.validUntil = validUntil;
    }

    public UUID getQuotationId() { return quotationId; }
    public UUID getTenantId() { return tenantId; }
    public String getOwnerSubject() { return ownerSubject; }
    public UUID getApplicantPartyId() { return applicantPartyId; }
    public UUID getProductId() { return productId; }
    public UUID getProductVersionId() { return productVersionId; }
    public String getInputs() { return inputs; }
    public String getQuoted() { return quoted; }
    public LocalDate getQuotedOn() { return quotedOn; }
    public LocalDate getValidUntil() { return validUntil; }
    public UUID getCaseId() { return caseId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSubmittedAt() { return submittedAt; }
}
