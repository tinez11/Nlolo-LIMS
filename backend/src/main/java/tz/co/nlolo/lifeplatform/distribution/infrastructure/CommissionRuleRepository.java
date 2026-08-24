package tz.co.nlolo.lifeplatform.distribution.infrastructure;

import tz.co.nlolo.lifeplatform.distribution.domain.CommissionRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Every method is tenant-scoped -- RLS is the backstop, not the only guard. */
public interface CommissionRuleRepository extends JpaRepository<CommissionRule, UUID> {
    List<CommissionRule> findByCommissionPlanIdAndTenantId(UUID commissionPlanId, UUID tenantId);
}
