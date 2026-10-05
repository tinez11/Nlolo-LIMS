package tz.co.nlolo.lifeplatform.policy.domain;

import tz.co.nlolo.lifeplatform.policy.api.InvalidPolicyStateException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A customer surrender in flight (V29-era step 1, task 4). REQUESTED by one staff member, APPROVED
 * by a different one (the two-person rule claims settlement also enforces), then PAID, FAILED or
 * IN_DOUBT once the payout returns. Approval is what stops cover and asks payment to disburse.
 */
@Entity
@Table(name = "surrender_request", schema = "policy")
public class SurrenderRequest {

    @Id
    @UuidGenerator
    @Column(name = "surrender_request_id")
    private UUID surrenderRequestId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "policy_number", nullable = false)
    private String policyNumber;

    @Column(nullable = false)
    private String status = "REQUESTED";

    // Null only on a unit-linked surrender, which is priced forward after approval (policy V36, plan R11).
    @Column(name = "quoted_value_amount")
    private BigDecimal quotedValueAmount;

    @Column(name = "quoted_value_currency", nullable = false)
    private String quotedValueCurrency = "TZS";

    @Column(name = "payee_ref", nullable = false)
    private String payeeRef;

    @Column(name = "requested_by", nullable = false)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt = Instant.now();

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "disbursement_id")
    private UUID disbursementId;

    protected SurrenderRequest() {}

    public SurrenderRequest(UUID tenantId, String policyNumber, BigDecimal quotedValueAmount,
                            String quotedValueCurrency, String payeeRef, String requestedBy) {
        // surrenderRequestId is intentionally not set here: @UuidGenerator assigns it on persist().
        // Setting it manually here would make isNew() return false, causing JPA to call merge()
        // instead of persist(), and Hibernate's before-insert generator would then assign a
        // different UUID than the one pre-set -- the returned view ID and the stored row ID would
        // diverge. See Claim.java for the canonical pattern in this codebase.
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.quotedValueAmount = quotedValueAmount;
        this.quotedValueCurrency = quotedValueCurrency;
        this.payeeRef = payeeRef;
        this.requestedBy = requestedBy;
    }

    /** Approve, by someone other than the requester (two-person rule). Moves REQUESTED -> APPROVED. */
    /**
     * A unit-linked surrender (product step 6): no quoted value, because the units are sold at the first price after
     * approval -- any figure here would be a price already known. Every other surrender keeps the constructor above.
     */
    public static SurrenderRequest forUnitLinked(UUID tenantId, String policyNumber, String currency, String payeeRef,
                                                 String requestedBy) {
        return new SurrenderRequest(tenantId, policyNumber, null, currency, payeeRef, requestedBy);
    }

    public void approve(String approvedBy) {
        if (!"REQUESTED".equals(status)) {
            throw new InvalidPolicyStateException("Surrender request " + surrenderRequestId
                + " is " + status + ", not awaiting approval");
        }
        if (approvedBy == null || approvedBy.equals(requestedBy)) {
            throw new InvalidPolicyStateException("A surrender must be approved by someone other than"
                + " the person who requested it (" + requestedBy + ")");
        }
        this.status = "APPROVED";
        this.approvedBy = approvedBy;
        this.approvedAt = Instant.now();
    }

    public void markPaid(UUID disbursementId) {
        this.status = "PAID";
        this.disbursementId = disbursementId;
    }

    public void markFailed() { this.status = "FAILED"; }

    public void markInDoubt() { this.status = "IN_DOUBT"; }

    public UUID getSurrenderRequestId() { return surrenderRequestId; }
    public String getPolicyNumber() { return policyNumber; }
    public String getStatus() { return status; }
    public BigDecimal getQuotedValueAmount() { return quotedValueAmount; }
    public String getQuotedValueCurrency() { return quotedValueCurrency; }
    public String getPayeeRef() { return payeeRef; }
    public String getRequestedBy() { return requestedBy; }
    public String getApprovedBy() { return approvedBy; }
}
