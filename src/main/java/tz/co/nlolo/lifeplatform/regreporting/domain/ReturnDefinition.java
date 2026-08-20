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
