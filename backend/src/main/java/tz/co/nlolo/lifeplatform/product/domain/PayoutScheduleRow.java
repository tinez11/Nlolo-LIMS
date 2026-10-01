package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.product.api.*;

import java.math.BigDecimal;
import java.util.UUID;

/** One authored payout on a product version (product step 2). */
@Entity
@Table(name = "payout_schedule_row", schema = "product")
public class PayoutScheduleRow {

    @Id
    @UuidGenerator
    @Column(name = "payout_row_id")
    private UUID payoutRowId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "product_version_id", nullable = false)
    private UUID productVersionId;

    /** Authoring order, and the stable key a policy's instalments carry back to this row. */
    @Column(name = "row_order", nullable = false)
    private int rowOrder;

    @Column(nullable = false)
    private String kind;

    @Column(name = "from_policy_year")
    private Integer fromPolicyYear;

    @Column(name = "to_policy_year")
    private Integer toPolicyYear;

    @Column(name = "amount_basis", nullable = false)
    private String amountBasis;

    @Column(name = "amount_value", nullable = false)
    private BigDecimal amountValue;

    @Column
    private String frequency;

    protected PayoutScheduleRow() {}

    public PayoutScheduleRow(UUID tenantId, UUID productVersionId, int rowOrder, PayoutRowInput in) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.rowOrder = rowOrder;
        this.kind = in.kind().name();
        this.fromPolicyYear = in.fromPolicyYear();
        this.toPolicyYear = in.toPolicyYear();
        this.amountBasis = in.amountBasis().name();
        this.amountValue = in.amountValue();
        this.frequency = in.frequency() != null ? in.frequency().name() : null;
    }

    public int getRowOrder() { return rowOrder; }

    public PayoutRowInput toInput() {
        return new PayoutRowInput(PayoutKind.valueOf(kind), fromPolicyYear, toPolicyYear,
            PayoutAmountBasis.valueOf(amountBasis), amountValue,
            frequency != null ? PayoutFrequency.valueOf(frequency) : null);
    }
}
