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
