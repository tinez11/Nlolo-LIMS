package tz.co.nlolo.lifeplatform.regreporting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

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

    /** Cancelled inside the free-look window, counted in the quarter the policy was ISSUED in.
     *  Deliberately not a termination -- see {@link #applyCancelledFromInception}. */
    @Column(name = "policies_cancelled_free_look", nullable = false)
    private int policiesCancelledFreeLook = 0;

    @Column(name = "sum_assured_terminated", nullable = false)
    private BigDecimal sumAssuredTerminated = BigDecimal.ZERO;

    /** Cover added by members joining a scheme after activation. Read by SUM_ASSURED_IN_FORCE and
     * deliberately not by NEW_BUSINESS_SUM_ASSURED -- see db-migrations/regreporting/V5. */
    @Column(name = "sum_assured_member_added", nullable = false)
    private BigDecimal sumAssuredMemberAdded = BigDecimal.ZERO;

    /** Cover removed by members leaving. Excludes the final close-out when the LAST member goes:
     * that restates the scheme to zero and closes it, and the resulting PolicySurrendered lands
     * in sumAssuredTerminated instead. */
    @Column(name = "sum_assured_member_exited", nullable = false)
    private BigDecimal sumAssuredMemberExited = BigDecimal.ZERO;

    @Column(name = "updated_at")
    private Instant updatedAt;

    /**
     * Optimistic lock (regreporting/V3, M10 final review C1) -- the SAME pattern {@code
     * RegulatoryReturn} uses. This row is maintained by read-modify-write (find -> {@code apply*}
     * -> save) in {@code PolicyEventListener}, so without a version two concurrent events for the
     * same {@code (tenant, period, product)} could both read the same counter, both increment it,
     * and lose one write SILENTLY. The listener retries a bounded 3 attempts on the resulting
     * {@code ObjectOptimisticLockingFailureException}, re-fetching fresh each time.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

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

    /**
     * A free-look cancellation: <b>reverse the issuance, record no termination.</b>
     *
     * <p>The ONLY measure here that subtracts, and the reason is that a free-look cancellation is
     * the one termination-shaped event that is not a termination. The customer exercised a
     * statutory right inside the cooling-off window and the contract is void from inception, so in
     * law it was never written.
     *
     * <p>Adding to {@code sumAssuredTerminated} instead -- via {@link #applyLapsed} or
     * {@link #applyMatured} -- would report the same contract as BOTH written and terminated,
     * inflating gross new business and gross terminations at once and computing persistency over
     * policies that never existed. So the issuance is backed out and the cohort simply does not
     * contain it.
     *
     * <p>The counter is incremented as well as the reversal applied, because a quarter whose new
     * business silently shrank gives an actuary reconciling it a gap with no cause. This is the
     * cause, and it makes cooling-off volume reportable on its own.
     *
     * <p>Cannot go negative in practice: the caller keys this row by the policy's ISSUE date, the
     * same date {@code applyIssued}'s caller used, so every reversal has a matching increment. The
     * {@code policy_movement_non_negative} CHECK is the backstop for a redelivered event.
     */
    public void applyCancelledFromInception(BigDecimal sumAssured) {
        this.policiesIssued--;
        this.sumAssuredIssued = this.sumAssuredIssued.subtract(sumAssured);
        this.policiesCancelledFreeLook++;
        this.updatedAt = Instant.now();
    }

    /**
     * Cover a joining member brought onto a scheme.
     *
     * <p><b>Moves no policy count</b>, unlike every other measure on this entity, and that is the
     * point rather than an omission: a scheme is ONE policy however many borrowers sit on it.
     * Incrementing a count here would report one lender's monthly file as several hundred new
     * contracts.
     *
     * <p>Takes a POSITIVE magnitude. The direction is carried by which method you call, exactly
     * as it is for the terminated measures, and the column's CHECK enforces it besides.
     */
    public void applyMemberCoverAdded(BigDecimal amount) {
        this.sumAssuredMemberAdded = this.sumAssuredMemberAdded.add(requireGrossMagnitude(amount));
        this.updatedAt = Instant.now();
    }

    /** Cover a leaving member took off a scheme. Positive magnitude and no policy count, as
     * above. */
    public void applyMemberCoverExited(BigDecimal amount) {
        this.sumAssuredMemberExited = this.sumAssuredMemberExited.add(requireGrossMagnitude(amount));
        this.updatedAt = Instant.now();
    }

    /**
     * Fails HERE rather than at the CHECK constraint, so the error names the call rather than the
     * column. A caller handing a signed delta straight through is the mistake this catches.
     *
     * <p>Zero is allowed, matching the column: {@code policy_movement_non_negative} is {@code >= 0}
     * and not {@code > 0} because an upsert creates the row with zeros and increments exactly one
     * measure, so zero is the normal value for every other cause in that period.
     */
    private static BigDecimal requireGrossMagnitude(BigDecimal amount) {
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException(
                "A movement measure is a gross non-negative magnitude; got " + amount);
        }
        return amount;
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
    public int getPoliciesCancelledFreeLook() { return policiesCancelledFreeLook; }
    public BigDecimal getSumAssuredTerminated() { return sumAssuredTerminated; }
    public BigDecimal getSumAssuredMemberAdded() { return sumAssuredMemberAdded; }
    public BigDecimal getSumAssuredMemberExited() { return sumAssuredMemberExited; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getComputedAt() { return computedAt; }
    public Long getVersion() { return version; }
}
