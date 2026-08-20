package tz.co.nlolo.lifeplatform.regreporting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps {@code regreporting.return_definition} -- the DATA half of a return's format
 * (design spec §5, V2 section 7). Line COMPOSITION is data (this table and
 * {@link ReturnDefinitionLine}); metric COMPUTATION is code (the MetricReaderRegistry).
 *
 * <p>{@code periodKind} pins a return type to one period format so an annual and a quarterly
 * period can never be cumulative-summed together.
 *
 * <p><b>{@code QUARTERLY} is the only IMPLEMENTED kind.</b> The DB CHECK (V2 section 7) also admits
 * {@code 'ANNUAL'}, and that is deliberately retained as a SCHEMA-LEVEL PLACEHOLDER for a future
 * capability -- but nothing in this module writes an annual-shaped period, so {@code ReturnGenerator}
 * REJECTS an {@code ANNUAL} definition outright (M10 final review, C2) instead of silently reporting
 * zeros for every flow metric and a year-stale position for every stock metric, which is what it did
 * before. Implementing annual returns needs to know what TIRA's annual return actually asks for
 * (C2-blocked), so do not seed an {@code ANNUAL} definition until that code exists. See
 * regreporting/V3's {@code COMMENT ON COLUMN return_definition.period_kind}.
 */
@Entity
@Table(name = "return_definition", schema = "regreporting")
@IdClass(ReturnDefinitionId.class)
public class ReturnDefinition {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Id
    @Column(name = "return_type")
    private String returnType;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "description")
    private String description;

    @Column(name = "period_kind", nullable = false)
    private String periodKind;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ReturnDefinition() {}

    public ReturnDefinition(UUID tenantId, String returnType, String label, String description, String periodKind) {
        this.tenantId = tenantId;
        this.returnType = returnType;
        this.label = label;
        this.description = description;
        this.periodKind = periodKind;
    }

    public UUID getTenantId() { return tenantId; }
    public String getReturnType() { return returnType; }
    public String getLabel() { return label; }
    public String getDescription() { return description; }
    public String getPeriodKind() { return periodKind; }
    public Instant getCreatedAt() { return createdAt; }
}
