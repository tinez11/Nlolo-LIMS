package tz.co.nlolo.lifeplatform.distribution.infrastructure;

import tz.co.nlolo.lifeplatform.distribution.api.TierType;
import tz.co.nlolo.lifeplatform.distribution.domain.CommissionAccrual;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Every method is tenant-scoped -- RLS is the backstop, not the only guard. */
public interface CommissionAccrualRepository extends JpaRepository<CommissionAccrual, UUID> {
    List<CommissionAccrual> findByStatementIdAndTenantId(UUID statementId, UUID tenantId);
    List<CommissionAccrual> findByTenantIdAndAgentIdAndPeriod(UUID tenantId, UUID agentId, String period);
    /** What one policy has earned, and clawed back, oldest first. */
    List<CommissionAccrual> findByTenantIdAndPolicyNumberOrderByCreatedAtAsc(UUID tenantId, String policyNumber);
    /** Clawback target lookup: the unreversed FIRST_YEAR accrual for a policy. */
    List<CommissionAccrual> findByTenantIdAndPolicyNumberAndTierTypeAndReversesAccrualIdIsNull(
        UUID tenantId, String policyNumber, TierType tierType);
    /**
     * Every unreversed EARNING on a policy, any tier -- what a free-look cancellation claws back,
     * because it undoes the sale from inception rather than ending a contract that ran.
     *
     * <p>{@code reversesAccrualIdIsNull} already excludes the clawback rows, which carry the id of
     * what they reverse. The {@code amountGreaterThan} zero clause is a second guard: it skips a
     * zero accrual, where a reversal would be a meaningless row, and makes it impossible for this
     * query to hand back anything whose negation would PAY an agent.
     */
    List<CommissionAccrual> findByTenantIdAndPolicyNumberAndReversesAccrualIdIsNullAndAmountGreaterThan(
        UUID tenantId, String policyNumber, java.math.BigDecimal amount);
    boolean existsByTenantIdAndAgentIdAndTierTypeAndSourceRefAndReversesAccrualIdIsNull(
        UUID tenantId, UUID agentId, TierType tierType, String sourceRef);
    /**
     * The clawback idempotency pre-check: {@code ux_commission_accrual_single_reversal_per_source}'s
     * own application-layer mirror, so a redelivered event does not even attempt a second reversal
     * row for the same original accrual from the same source (the unique index is the real
     * backstop under concurrent delivery).
     *
     * <p>Keyed on the source as well as the accrual because one accrual may now be reversed in
     * PARTS. A lapse reverses the whole thing and passes the accrual's own id as the source, so
     * it still gets exactly one reversal; a credit-life refund reverses one borrower's share and
     * passes that member's id, so four hundred borrowers settling early produce four hundred
     * distinct partial reversals of the one accrual their file earned.
     */
    boolean existsByTenantIdAndReversesAccrualIdAndSourceRef(
        UUID tenantId, UUID reversesAccrualId, String sourceRef);
}
