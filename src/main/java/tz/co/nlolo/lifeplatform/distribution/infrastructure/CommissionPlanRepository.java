package tz.co.nlolo.lifeplatform.distribution.infrastructure;

import tz.co.nlolo.lifeplatform.distribution.domain.CommissionPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every method is tenant-scoped -- RLS is the backstop, not the only guard. */
public interface CommissionPlanRepository extends JpaRepository<CommissionPlan, UUID> {
    Optional<CommissionPlan> findByCommissionPlanIdAndTenantId(UUID commissionPlanId, UUID tenantId);
    List<CommissionPlan> findByTenantIdAndProductIdAndStatus(UUID tenantId, UUID productId, String status);
}
