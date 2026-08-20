package tz.co.nlolo.lifeplatform.regreporting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps {@code regreporting.return_line} -- one line of a generated return (V2 section 8).
 *
 * <p>{@code returnId} is a plain {@code UUID} column, not a JPA relationship: the parent
 * {@link RegulatoryReturn} is saved first (so its {@code @GeneratedValue} id becomes real), and its
 * lines are built and saved afterwards -- the same shape {@code finaccounting.JournalEntry}/
 * {@code GlPosting} use for the identical reason (parent id does not exist until persisted).
 *
 * <p>{@code numericValue} carries no non-negativity check, deliberately: a legitimate DERIVED
 * figure (e.g. a cumulative {@code POLICIES_IN_FORCE}) can be negative when a period's
 * terminations exceed its issuances -- a real business outcome, not corrupt data.
 */
@Entity
@Table(name = "return_line", schema = "regreporting")
public class ReturnLine {

    @Id
    @GeneratedValue
    @Column(name = "return_line_id")
    private UUID returnLineId;

    /** Plain column referencing the parent {@link RegulatoryReturn#getReturnId()} -- see class javadoc. */
    @Column(name = "return_id", nullable = false)
    private UUID returnId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "line_code", nullable = false)
    private String lineCode;

    @Column(name = "label", nullable = false)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "metric_name", nullable = false)
    private MetricName metricName;

    @Column(name = "numeric_value", nullable = false)
    private BigDecimal numericValue;

    /** Null for a count; set for a money figure. */
    @Column(name = "currency")
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ReturnLine() {}

    public ReturnLine(UUID tenantId, UUID returnId, int lineNo, String lineCode, String label,
                       MetricName metricName, BigDecimal numericValue, String currency) {
        this.tenantId = tenantId;
        this.returnId = returnId;
        this.lineNo = lineNo;
        this.lineCode = lineCode;
        this.label = label;
        this.metricName = metricName;
        this.numericValue = numericValue;
        this.currency = currency;
    }

    public UUID getReturnLineId() { return returnLineId; }
    public UUID getReturnId() { return returnId; }
    public UUID getTenantId() { return tenantId; }
    public int getLineNo() { return lineNo; }
    public String getLineCode() { return lineCode; }
    public String getLabel() { return label; }
    public MetricName getMetricName() { return metricName; }
    public BigDecimal getNumericValue() { return numericValue; }
    public String getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
}
