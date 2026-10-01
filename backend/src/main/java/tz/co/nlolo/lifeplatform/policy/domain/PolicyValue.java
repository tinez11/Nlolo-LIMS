package tz.co.nlolo.lifeplatform.policy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * How far a savings policy's premiums are paid (V29), recomputed on each collected premium. It is
 * what paid-up and cash value read: the policy year reached, and the proportion of the premium term
 * completed. Written only for savings policies, so a pure-protection policy never has a row.
 */
@Entity
@Table(name = "policy_value", schema = "policy")
public class PolicyValue {

    @Id
    @Column(name = "policy_number")
    private String policyNumber;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "paid_to_date")
    private LocalDate paidToDate;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt = Instant.now();

    protected PolicyValue() {}

    public PolicyValue(String policyNumber, UUID tenantId, LocalDate paidToDate) {
        this.policyNumber = policyNumber;
        this.tenantId = tenantId;
        this.paidToDate = paidToDate;
    }

    public LocalDate getPaidToDate() { return paidToDate; }

    public void restatePaidToDate(LocalDate paidToDate) {
        this.paidToDate = paidToDate;
        this.computedAt = Instant.now();
    }
}
