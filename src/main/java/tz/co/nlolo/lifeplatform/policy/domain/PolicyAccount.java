package tz.co.nlolo.lifeplatform.policy.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "policy_account", schema = "policy")
public class PolicyAccount {

    @Id
    @Column(name = "policy_number")
    private String policyNumber;

    @Column(name = "tenant_id", nullable = false)
    private java.util.UUID tenantId;

    @Column(name = "cash_value_amount", nullable = false)
    private BigDecimal cashValueAmount = BigDecimal.ZERO;

    @Column(name = "cash_value_currency", nullable = false)
    private String cashValueCurrency = "TZS";

    // Non-authoritative projection (see Global Constraints: updated synchronously inside
    // PolicyApiImpl.confirmReservation, not via async event consumption of LoanOriginated).
    @Column(name = "loan_encumbrance_amount", nullable = false)
    private BigDecimal loanEncumbranceAmount = BigDecimal.ZERO;

    @Version
    private Long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected PolicyAccount() {}

    public PolicyAccount(String policyNumber, java.util.UUID tenantId, BigDecimal cashValueAmount, String cashValueCurrency) {
        this.policyNumber = policyNumber;
        this.tenantId = tenantId;
        this.cashValueAmount = cashValueAmount;
        this.cashValueCurrency = cashValueCurrency;
    }

    public String getPolicyNumber() { return policyNumber; }
    public java.util.UUID getTenantId() { return tenantId; }
    public BigDecimal getCashValueAmount() { return cashValueAmount; }
    public String getCashValueCurrency() { return cashValueCurrency; }
    public BigDecimal getLoanEncumbranceAmount() { return loanEncumbranceAmount; }

    public BigDecimal availableLoanValue(BigDecimal currentlyReserved) {
        // Deliberate M3 simplification (Global Constraints): available value is cash value net
        // of confirmed encumbrance and currently-RESERVED holds -- NOT further capped by
        // product_version.max_loan_to_value_percent, which would require issuePolicy to persist
        // that percentage onto PolicyAccount/Policy and is not required to prove the
        // Module-Architecture-B1 race-condition fix, this milestone's actual acceptance
        // criterion. Flagged, not silently dropped -- a future milestone can apply the LTV cap
        // as a further multiplier on cashValueAmount before this subtraction.
        return cashValueAmount.subtract(loanEncumbranceAmount).subtract(currentlyReserved);
    }

    public void increaseEncumbrance(BigDecimal amount) {
        this.loanEncumbranceAmount = this.loanEncumbranceAmount.add(amount);
    }
}
