package tz.co.nlolo.lifeplatform.regreporting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps {@code regreporting.return_definition_line} -- one line's composition within a
 * {@link ReturnDefinition} (design spec §5, V2 section 7). {@code metricName} is intentionally a
 * plain {@code String} here, NOT the {@link MetricName} enum: the row is DATA (seeded, and later
 * per-tenant onboarding data), so its value is only a candidate metric name until something
 * resolves it. That resolution is {@code ReturnGenerator}'s job (Task 5) -- it calls
 * {@code MetricName.valueOf(...)} itself and rethrows the enum's {@code IllegalArgumentException}
 * as a {@code RegreportingValidationException} naming the bad metric. Mapping this field as
 * {@code @Enumerated(EnumType.STRING) MetricName} instead (as {@link ReturnLine#getMetricName()}
 * does, correctly, for its OWN already-validated value) would move that failure into Hibernate's
 * own enum conversion inside the repository call, before generation-time code ever runs, and with
 * an exception type generation-time code cannot cleanly catch and rename -- exactly the silent,
 * hard-to-attribute failure this field must not produce.
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

    /** A candidate {@link MetricName} name, unresolved -- see the class javadoc for why this is
     * a {@code String} and not the enum itself. */
    @Column(name = "metric_name", nullable = false)
    private String metricName;

    /** A product_id, or a claim_type; null = unfiltered. */
    @Column(name = "dimension_filter")
    private String dimensionFilter;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ReturnDefinitionLine() {}

    public ReturnDefinitionLine(UUID tenantId, String returnType, int lineNo, String lineCode,
                                 String label, String metricName, String dimensionFilter) {
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
    public String getMetricName() { return metricName; }
    public String getDimensionFilter() { return dimensionFilter; }
    public Instant getCreatedAt() { return createdAt; }
}
