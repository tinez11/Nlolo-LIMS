package tz.co.nlolo.lifeplatform.distribution.infrastructure;

import tz.co.nlolo.lifeplatform.distribution.api.PlanStatus;
import tz.co.nlolo.lifeplatform.distribution.domain.CommissionPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every method is tenant-scoped -- RLS is the backstop, not the only guard. */
public interface CommissionPlanRepository extends JpaRepository<CommissionPlan, UUID> {
    Optional<CommissionPlan> findByCommissionPlanIdAndTenantId(UUID commissionPlanId, UUID tenantId);
    /** {@code status} is a {@link PlanStatus} enum (Task 5 -- see {@link CommissionPlan}'s own
     * javadoc); passing the enum itself, never {@code status.name()}, matches this platform's
     * established rule for {@code @Enumerated(STRING)} derived-query parameters (see
     * {@code ClaimRepository}'s own javadoc on the same pitfall). */
    List<CommissionPlan> findByTenantIdAndProductIdAndStatus(UUID tenantId, UUID productId, PlanStatus status);
}
