package tz.co.nlolo.lifeplatform.reinsurance.infrastructure;

import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyStatus;
import tz.co.nlolo.lifeplatform.reinsurance.domain.ReinsuranceTreaty;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReinsuranceTreatyRepository extends JpaRepository<ReinsuranceTreaty, UUID> {
    Optional<ReinsuranceTreaty> findByTreatyIdAndTenantId(UUID treatyId, UUID tenantId);
    List<ReinsuranceTreaty> findByTenantIdOrderByEffectiveFromDesc(UUID tenantId);
    List<ReinsuranceTreaty> findByTenantIdAndStatusOrderByEffectiveFromDesc(UUID tenantId, TreatyStatus status);
}
