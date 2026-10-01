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

    /**
     * Restate the cash value from the product's cash-value table as premiums are paid (step 1).
     * Set, not added: cash value is a function of the contract at a policy year, read from the
     * table, not a running total -- so each premium recomputes it rather than incrementing it.
     * Refuses a negative (the table's per-mille is non-negative; a negative here is a bug upstream)
     * and updates the touch timestamp so the change is auditable.
     */
    public void restateCashValue(BigDecimal amount) {
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException(
                "Cash value cannot be negative, was: " + amount + " (policy " + policyNumber + ")");
        }
        this.cashValueAmount = amount;
        this.updatedAt = Instant.now();
    }
    public String getCashValueCurrency() { return cashValueCurrency; }
    public BigDecimal getLoanEncumbranceAmount() { return loanEncumbranceAmount; }

    /** Uncapped: cash value net of encumbrance and holds. Kept for callers that have no LTV to apply. */
    public BigDecimal availableLoanValue(BigDecimal currentlyReserved) {
        return availableLoanValue(currentlyReserved, null);
    }

    /**
     * Available loan value, capped by the product's loan-to-value percent (step 1). The borrowable
     * base is cash value × LTV% (the whole cash value when the percent is null or 100+), then net of
     * confirmed encumbrance and currently-RESERVED holds. The M3 simplification that ignored the LTV
     * is closed: the percent is read from the policy's own product version and passed here.
     */
    public BigDecimal availableLoanValue(BigDecimal currentlyReserved, BigDecimal maxLoanToValuePercent) {
        BigDecimal base = cashValueAmount;
        if (maxLoanToValuePercent != null && maxLoanToValuePercent.compareTo(new BigDecimal("100")) < 0) {
            base = cashValueAmount.multiply(maxLoanToValuePercent)
                .divide(new BigDecimal("100"), 2, java.math.RoundingMode.DOWN);
        }
        return base.subtract(loanEncumbranceAmount).subtract(currentlyReserved);
    }

    /**
     * The aggregate's own invariant, not merely a service-layer precondition (M3 final review,
     * I3). This method was an unguarded {@code .add()}: the exact mechanism of Task 7's
     * Critical, where a negative amount arriving here <em>reduces</em> the encumbrance and so
     * <em>raises</em> the policyholder's available loan value, letting them borrow beyond
     * surrender value. That was closed at the wire (policyloan's MoneyDto {@code @DecimalMin})
     * and now at the service boundary (PolicyApiImpl.reserveLoanValue's sign guard), but an
     * invariant this load-bearing belongs on the entity that owns the column -- the amount
     * reaching here has travelled through a reservation row and a confirm call, so "the caller
     * already checked" is not something this class can verify. Rejecting rather than clamping:
     * a negative encumbrance is always a bug upstream, and silently coercing it to zero would
     * hide it.
     *
     * <p>Zero is rejected too, matching the wire-level {@code @DecimalMin("0.01")} and
     * db-migrations/policy/V2's {@code CHECK (amount > 0)} on loan_value_reservation -- there
     * is no legitimate zero-value loan reservation.
     */
    public void increaseEncumbrance(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException(
                "Encumbrance increase must be a positive amount, was: " + amount + " (policy " + policyNumber + ")");
        }
        this.loanEncumbranceAmount = this.loanEncumbranceAmount.add(amount);
    }

    /**
     * The compensating half of increaseEncumbrance, added in M5 for the DisbursementFailed path.
     * Rejects rather than clamps, exactly as increaseEncumbrance does: an over-release would
     * silently hand back loan value that was never encumbered, and a negative encumbrance is
     * always an upstream bug that must surface here rather than be coerced away.
     */
    public void decreaseEncumbrance(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Encumbrance release amount must be positive, got: " + amount);
        }
        if (loanEncumbranceAmount.compareTo(amount) < 0) {
            throw new IllegalStateException("Cannot release " + amount + " of encumbrance on policy "
                + policyNumber + " -- only " + loanEncumbranceAmount + " is encumbered");
        }
        this.loanEncumbranceAmount = this.loanEncumbranceAmount.subtract(amount);
    }
}
