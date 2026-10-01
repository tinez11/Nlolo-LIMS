package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Per-version cash-value configuration (V17). Its presence marks a version as a savings product.
 *
 * <p>{@code basisReference}/{@code basisDate} are the actuarial sign-off -- a value table cannot be
 * published without one, the discipline the TIRA filing already enforces for pricing.
 * {@code minYearsForValue} (2 or 3) is the years before any surrender or paid-up value exists.
 * {@code paidUpBasis} is how a paid-up sum assured is found: PROPORTIONATE (no table needed) or
 * TABLE (from {@code cash_value_table.paid_up_per_mille}).
 */
@Entity
@Table(name = "cash_value_config", schema = "product")
public class CashValueConfig {

    @Id
    @Column(name = "product_version_id")
    private UUID productVersionId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "basis_reference", nullable = false)
    private String basisReference;

    @Column(name = "basis_date", nullable = false)
    private LocalDate basisDate;

    @Column(name = "paid_up_basis", nullable = false)
    private String paidUpBasis;

    @Column(name = "min_years_for_value", nullable = false)
    private int minYearsForValue;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected CashValueConfig() {}

    public CashValueConfig(UUID productVersionId, UUID tenantId, String basisReference, LocalDate basisDate,
                           String paidUpBasis, int minYearsForValue) {
        this.productVersionId = productVersionId;
        this.tenantId = tenantId;
        this.basisReference = basisReference;
        this.basisDate = basisDate;
        this.paidUpBasis = paidUpBasis;
        this.minYearsForValue = minYearsForValue;
    }

    public UUID getProductVersionId() { return productVersionId; }
    public String getBasisReference() { return basisReference; }
    public LocalDate getBasisDate() { return basisDate; }
    public String getPaidUpBasis() { return paidUpBasis; }
    public int getMinYearsForValue() { return minYearsForValue; }
}
