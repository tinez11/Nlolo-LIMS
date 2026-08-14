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
    /** Clawback target lookup: the unreversed FIRST_YEAR accrual for a policy. */
    List<CommissionAccrual> findByTenantIdAndPolicyNumberAndTierTypeAndReversesAccrualIdIsNull(
        UUID tenantId, String policyNumber, TierType tierType);
    boolean existsByTenantIdAndAgentIdAndTierTypeAndSourceRefAndReversesAccrualIdIsNull(
        UUID tenantId, UUID agentId, TierType tierType, String sourceRef);
    /** Task 6's clawback idempotency pre-check: {@code ux_commission_accrual_single_reversal}'s
     * own application-layer mirror, so a redelivered {@code PolicyLapsed} does not even attempt a
     * second reversal row for the same original accrual (the unique index is the real backstop
     * under concurrent delivery). */
    boolean existsByTenantIdAndReversesAccrualId(UUID tenantId, UUID reversesAccrualId);
}
