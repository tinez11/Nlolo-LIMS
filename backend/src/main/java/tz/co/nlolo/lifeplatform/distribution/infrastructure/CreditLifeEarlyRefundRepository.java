package tz.co.nlolo.lifeplatform.distribution.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.distribution.domain.CreditLifeEarlyRefund;

import java.util.List;
import java.util.UUID;

/** Every method is tenant-scoped -- RLS is the backstop, not the only guard. */
public interface CreditLifeEarlyRefundRepository
        extends JpaRepository<CreditLifeEarlyRefund, CreditLifeEarlyRefund.Key> {
    List<CreditLifeEarlyRefund> findByTenantIdAndSubmissionId(UUID tenantId, UUID submissionId);

    boolean existsByTenantIdAndSubmissionIdAndPolicyMemberId(UUID tenantId, UUID submissionId, UUID policyMemberId);
}
