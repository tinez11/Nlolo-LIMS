package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One row of a version's cash-value scale (V17): what a policy is worth per 1,000 of sum assured at
 * a given policy year, and -- on the TABLE paid-up basis -- the reduced sum assured per 1,000 if the
 * customer stops paying. Age bands optional, the base-rate convention: null means one scale for
 * every entry age.
 *
 * <p>A traditional cash value is a function of the contract read from a table the actuary supplies,
 * not a running balance of premiums and charges. A unit-linked account value (a real ledger) is a
 * different object and belongs to a later step.
 */
@Entity
@Table(name = "cash_value_table", schema = "product")
public class CashValueEntry {

    @Id
    @UuidGenerator
    @Column(name = "cash_value_id")
    private UUID cashValueId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "product_version_id", nullable = false)
    private UUID productVersionId;

    @Column(name = "policy_year", nullable = false)
    private int policyYear;

    @Column(name = "age_from")
    private Integer ageFrom;

    @Column(name = "age_to")
    private Integer ageTo;

    @Column(name = "cash_value_per_mille", nullable = false)
    private BigDecimal cashValuePerMille;

    @Column(name = "paid_up_per_mille")
    private BigDecimal paidUpPerMille;

    protected CashValueEntry() {}

    public CashValueEntry(UUID tenantId, UUID productVersionId, int policyYear, Integer ageFrom, Integer ageTo,
                          BigDecimal cashValuePerMille, BigDecimal paidUpPerMille) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.policyYear = policyYear;
        this.ageFrom = ageFrom;
        this.ageTo = ageTo;
        this.cashValuePerMille = cashValuePerMille;
        this.paidUpPerMille = paidUpPerMille;
    }

    public UUID getProductVersionId() { return productVersionId; }
    public int getPolicyYear() { return policyYear; }
    public Integer getAgeFrom() { return ageFrom; }
    public Integer getAgeTo() { return ageTo; }
    public BigDecimal getCashValuePerMille() { return cashValuePerMille; }
    public BigDecimal getPaidUpPerMille() { return paidUpPerMille; }
}
