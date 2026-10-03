package tz.co.nlolo.lifeplatform.benefitpayout.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.benefitpayout.api.PayoutStateException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A tax-withholding rule (product step 5, spec Q8): which payout kinds, what rate, from when to when,
 * under which law. Proposed by one finance user, approved by another; only an APPROVED rule withholds.
 */
@Entity
@Table(name = "withholding_rule", schema = "benefitpayout")
public class WithholdingRule {

    public enum Status { PROPOSED, APPROVED, WITHDRAWN }

    @Id @UuidGenerator @Column(name = "rule_id") private UUID ruleId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    /** Comma-separated payout kinds (benefitpayout V3). */
    @Column(name = "payout_kinds", nullable = false) private String payoutKinds;
    @Column(name = "rate_percent", nullable = false) private BigDecimal ratePercent;
    @Column(name = "effective_from", nullable = false) private LocalDate effectiveFrom;
    @Column(name = "effective_to") private LocalDate effectiveTo;
    @Column(name = "legal_reference", nullable = false) private String legalReference;
    @Column(nullable = false) private String status = Status.PROPOSED.name();
    @Column(name = "proposed_by", nullable = false) private String proposedBy;
    @Column(name = "proposed_at", nullable = false) private Instant proposedAt = Instant.now();
    @Column(name = "approved_by") private String approvedBy;
    @Column(name = "approved_at") private Instant approvedAt;
    @Column(name = "withdrawn_by") private String withdrawnBy;
    @Column(name = "idempotency_key", nullable = false) private String idempotencyKey;

    protected WithholdingRule() {}

    public WithholdingRule(UUID tenantId, List<String> payoutKinds, BigDecimal ratePercent, LocalDate effectiveFrom,
                           LocalDate effectiveTo, String legalReference, String proposedBy, String idempotencyKey) {
        this.tenantId = tenantId;
        this.payoutKinds = String.join(",", payoutKinds);
        this.ratePercent = ratePercent;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
        this.legalReference = legalReference;
        this.proposedBy = proposedBy;
        this.idempotencyKey = idempotencyKey;
    }

    public void approve(String approver) {
        if (status() != Status.PROPOSED) {
            throw new PayoutStateException("This withholding rule is " + status.toLowerCase() + ", not awaiting approval");
        }
        if (approver == null || approver.equals(proposedBy)) {
            throw new PayoutStateException("A withholding rule must be approved by someone other than the person who proposed it");
        }
        this.status = Status.APPROVED.name();
        this.approvedBy = approver;
        this.approvedAt = Instant.now();
    }

    public void withdraw(String by) {
        if (status() != Status.PROPOSED) {
            throw new PayoutStateException("Only a proposed withholding rule can be withdrawn; this one is " + status.toLowerCase());
        }
        this.status = Status.WITHDRAWN.name();
        this.withdrawnBy = by;
    }

    public boolean appliesTo(String kind, LocalDate date) {
        return status() == Status.APPROVED && getPayoutKinds().contains(kind)
            && !date.isBefore(effectiveFrom) && (effectiveTo == null || !date.isAfter(effectiveTo));
    }

    /** Whether two rules' date ranges and kinds overlap -- two approved rules may not. */
    public boolean overlaps(WithholdingRule other) {
        boolean sharedKind = getPayoutKinds().stream().anyMatch(k -> other.getPayoutKinds().contains(k));
        boolean datesMeet = (effectiveTo == null || !other.effectiveFrom.isAfter(effectiveTo))
            && (other.effectiveTo == null || !effectiveFrom.isAfter(other.effectiveTo));
        return sharedKind && datesMeet;
    }

    public Status status() { return Status.valueOf(status); }
    public UUID getRuleId() { return ruleId; }
    public List<String> getPayoutKinds() { return List.of(payoutKinds.split(",")); }
    public BigDecimal getRatePercent() { return ratePercent; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public LocalDate getEffectiveTo() { return effectiveTo; }
    public String getLegalReference() { return legalReference; }
    public String getProposedBy() { return proposedBy; }
    public Instant getProposedAt() { return proposedAt; }
    public String getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public String getIdempotencyKey() { return idempotencyKey; }
}
