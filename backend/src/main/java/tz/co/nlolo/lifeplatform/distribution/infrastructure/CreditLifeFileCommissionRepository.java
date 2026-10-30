package tz.co.nlolo.lifeplatform.distribution.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.distribution.domain.CreditLifeFileCommission;

import java.util.Optional;
import java.util.UUID;

/** Every method is tenant-scoped -- RLS is the backstop, not the only guard. */
public interface CreditLifeFileCommissionRepository extends JpaRepository<CreditLifeFileCommission, UUID> {
    Optional<CreditLifeFileCommission> findByTenantIdAndSubmissionId(UUID tenantId, UUID submissionId);
}
