package tz.co.nlolo.lifeplatform.regreporting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps {@code regreporting.return_definition_line} -- one line's composition within a
 * {@link ReturnDefinition} (design spec §5, V2 section 7). {@code metricName} must name one of the
 * seventeen {@link MetricName} values -- a definition naming something absent from that enum must
 * fail loudly at generation time, never emit a null line.
 *
 * <p>{@code dimensionFilter} holds either a {@code product_id} (as a UUID string) or a
 * {@code claim_type}; null means unfiltered.
 */
@Entity
@Table(name = "return_definition_line", schema = "regreporting")
@IdClass(ReturnDefinitionLineId.class)
public class ReturnDefinitionLine {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Id
    @Column(name = "return_type")
    private String returnType;

    @Id
    @Column(name = "line_no")
    private int lineNo;

    @Column(name = "line_code", nullable = false)
    private String lineCode;

    @Column(name = "label", nullable = false)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "metric_name", nullable = false)
    private MetricName metricName;

    /** A product_id, or a claim_type; null = unfiltered. */
    @Column(name = "dimension_filter")
    private String dimensionFilter;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ReturnDefinitionLine() {}

    public ReturnDefinitionLine(UUID tenantId, String returnType, int lineNo, String lineCode,
                                 String label, MetricName metricName, String dimensionFilter) {
        this.tenantId = tenantId;
        this.returnType = returnType;
        this.lineNo = lineNo;
        this.lineCode = lineCode;
        this.label = label;
        this.metricName = metricName;
        this.dimensionFilter = dimensionFilter;
    }

    public UUID getTenantId() { return tenantId; }
    public String getReturnType() { return returnType; }
    public int getLineNo() { return lineNo; }
    public String getLineCode() { return lineCode; }
    public String getLabel() { return label; }
    public MetricName getMetricName() { return metricName; }
    public String getDimensionFilter() { return dimensionFilter; }
    public Instant getCreatedAt() { return createdAt; }
}
