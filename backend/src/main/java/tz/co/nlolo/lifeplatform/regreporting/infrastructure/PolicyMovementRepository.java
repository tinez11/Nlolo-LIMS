package tz.co.nlolo.lifeplatform.regreporting.infrastructure;

import tz.co.nlolo.lifeplatform.regreporting.domain.PolicyMovement;
import tz.co.nlolo.lifeplatform.regreporting.domain.PolicyMovementId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PolicyMovementRepository extends JpaRepository<PolicyMovement, PolicyMovementId> {
    Optional<PolicyMovement> findByTenantIdAndPeriodAndProductId(UUID tenantId, String period, UUID productId);

    /** STOCK metrics (POLICIES_IN_FORCE, SUM_ASSURED_IN_FORCE) are a cumulative sum over every
     * period up to and including the reporting period -- see {@code MetricKind.STOCK}'s javadoc. */
    List<PolicyMovement> findByTenantIdAndPeriodLessThanEqual(UUID tenantId, String period);

    List<PolicyMovement> findByTenantIdAndPeriod(UUID tenantId, String period);
}
