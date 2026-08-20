package tz.co.nlolo.lifeplatform.regreporting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps {@code regreporting.regulatory_return} -- the header of one generated return
 * (V2 section 3). {@code ux_regulatory_return_once} on {@code (tenant_id, return_type, period)}
 * is what makes {@code generateReturn} idempotent: regenerating REPLACES the prior lines rather
 * than accumulating duplicates.
 *
 * <p>{@code returnId} is {@code @GeneratedValue} (this codebase's convention -- see
 * {@code reinsurance.Cession}), and {@code version} is optimistic-locked with {@code @Version}
 * so a concurrent regeneration cannot silently interleave with another.
 *
 * <p>{@code status} is always {@code "READY"}: under synchronous generation (design spec §7) a
 * return is complete when it is created, so {@code GENERATING} -- the DB CHECK's only other
 * admitted value -- is UNREACHABLE from this code.
 */
@Entity
@Table(name = "regulatory_return", schema = "regreporting")
public class RegulatoryReturn {

    @Id
    @GeneratedValue
    @Column(name = "return_id")
    private UUID returnId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "return_type", nullable = false)
    private String returnType;

    @Column(name = "period", nullable = false)
    private String period;

    /** Always "READY" -- see the class javadoc. */
    @Column(name = "status", nullable = false)
    private String status = "READY";

    /** C2-BLOCKED -- never written. Rendering a submission artifact requires TIRA's file format. */
    @Column(name = "document_ref")
    private String documentRef;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt = Instant.now();

    @Column(name = "generated_by")
    private String generatedBy;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected RegulatoryReturn() {}

    public RegulatoryReturn(UUID tenantId, String returnType, String period, String generatedBy) {
        this.tenantId = tenantId;
        this.returnType = returnType;
        this.period = period;
        this.generatedBy = generatedBy;
    }

    /** Regeneration replaces this header's timestamps in place rather than creating a new row --
     * see the class javadoc's {@code ux_regulatory_return_once} note. {@code status} stays
     * {@code "READY"} and {@code documentRef} stays untouched (C2-blocked). */
    public void regenerate(String generatedBy) {
        this.generatedAt = Instant.now();
        this.generatedBy = generatedBy;
    }

    public UUID getReturnId() { return returnId; }
    public UUID getTenantId() { return tenantId; }
    public String getReturnType() { return returnType; }
    public String getPeriod() { return period; }
    public String getStatus() { return status; }
    public String getDocumentRef() { return documentRef; }
    public Instant getGeneratedAt() { return generatedAt; }
    public String getGeneratedBy() { return generatedBy; }
    public Long getVersion() { return version; }
}
