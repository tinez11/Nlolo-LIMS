package tz.co.nlolo.lifeplatform.regreporting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps {@code regreporting.policy_movement} -- GROSS per-cause policy movements for one
 * {@code (tenant, period, product)}, not a snapshot of what is in force (V2 section 4).
 *
 * <p><b>The net movement is DERIVED, never stored.</b> A single signed delta cannot distinguish
 * "10 issued, 3 lapsed" from "7 issued, 0 lapsed" -- both net +7 -- and every prudential return
 * needs the gross new-business and gross-termination figures as separate lines. Storing only the
 * net would lose the gross irrecoverably; storing the gross (this table) lets the net be derived
 * by arithmetic whenever it is actually needed.
 *
 * <p>Every measure defaults to zero and is incremented by exactly one {@code apply*} method per
 * movement cause, matching the DB's {@code policy_movement_non_negative} CHECK: an upsert creates
 * the row with zeros and then increments ONE column, so zero is a normal value for every other
 * cause in that period.
 */
@Entity
@Table(name = "policy_movement", schema = "regreporting")
@IdClass(PolicyMovementId.class)
public class PolicyMovement {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Id
    @Column(name = "period")
    private String period;

    @Id
    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "policies_issued", nullable = false)
    private int policiesIssued = 0;

    @Column(name = "sum_assured_issued", nullable = false)
    private BigDecimal sumAssuredIssued = BigDecimal.ZERO;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "policies_reinstated", nullable = false)
    private int policiesReinstated = 0;

    @Column(name = "policies_lapsed", nullable = false)
    private int policiesLapsed = 0;

    @Column(name = "policies_matured", nullable = false)
    private int policiesMatured = 0;

    @Column(name = "policies_claim_terminated", nullable = false)
    private int policiesClaimTerminated = 0;

    @Column(name = "sum_assured_terminated", nullable = false)
    private BigDecimal sumAssuredTerminated = BigDecimal.ZERO;

    @Column(name = "updated_at")
    private Instant updatedAt;

    /**
     * Legacy column retained from V1's snapshot-model design ({@code policy_in_force_summary
     * .computed_at}); V2's rename-and-reshape (section 4) never renamed or dropped it, so it
     * still exists on the real table. Populated once at construction and never touched by later
     * {@code apply*} calls -- a gross-movement row is never "recomputed in place", only added to.
     */
    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    protected PolicyMovement() {}

    /** Creates the row for {@code (tenantId, period, productId)} with every measure at zero. */
    public PolicyMovement(UUID tenantId, String period, UUID productId, String currency) {
        this.tenantId = tenantId;
        this.period = period;
        this.productId = productId;
        this.currency = currency;
        this.computedAt = Instant.now();
    }

    public void applyIssued(BigDecimal sumAssured) {
        this.policiesIssued++;
        this.sumAssuredIssued = this.sumAssuredIssued.add(sumAssured);
        this.updatedAt = Instant.now();
    }

    public void applyReinstated() {
        this.policiesReinstated++;
        this.updatedAt = Instant.now();
    }

    public void applyLapsed(BigDecimal sumAssured) {
        this.policiesLapsed++;
        this.sumAssuredTerminated = this.sumAssuredTerminated.add(sumAssured);
        this.updatedAt = Instant.now();
    }

    public void applyMatured(BigDecimal sumAssured) {
        this.policiesMatured++;
        this.sumAssuredTerminated = this.sumAssuredTerminated.add(sumAssured);
        this.updatedAt = Instant.now();
    }

    public void applyClaimTerminated(BigDecimal sumAssured) {
        this.policiesClaimTerminated++;
        this.sumAssuredTerminated = this.sumAssuredTerminated.add(sumAssured);
        this.updatedAt = Instant.now();
    }

    public UUID getTenantId() { return tenantId; }
    public String getPeriod() { return period; }
    public UUID getProductId() { return productId; }
    public int getPoliciesIssued() { return policiesIssued; }
    public BigDecimal getSumAssuredIssued() { return sumAssuredIssued; }
    public String getCurrency() { return currency; }
    public int getPoliciesReinstated() { return policiesReinstated; }
    public int getPoliciesLapsed() { return policiesLapsed; }
    public int getPoliciesMatured() { return policiesMatured; }
    public int getPoliciesClaimTerminated() { return policiesClaimTerminated; }
    public BigDecimal getSumAssuredTerminated() { return sumAssuredTerminated; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getComputedAt() { return computedAt; }
}
