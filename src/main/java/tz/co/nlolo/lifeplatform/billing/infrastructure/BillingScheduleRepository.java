package tz.co.nlolo.lifeplatform.billing.infrastructure;

import tz.co.nlolo.lifeplatform.billing.domain.BillingSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BillingScheduleRepository extends JpaRepository<BillingSchedule, UUID> {
    Optional<BillingSchedule> findByPolicyNumberAndTenantIdAndStatus(String policyNumber, UUID tenantId, String status);
    List<BillingSchedule> findByPolicyNumberAndTenantId(String policyNumber, UUID tenantId);
}
