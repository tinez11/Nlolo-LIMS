package tz.co.nlolo.lifeplatform.product.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.product.domain.AccountCharge;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountChargeRepository extends JpaRepository<AccountCharge, UUID> {
    List<AccountCharge> findByTenantIdOrderByNameAsc(UUID tenantId);

    List<AccountCharge> findByTenantIdAndChargeIdIn(UUID tenantId, Collection<UUID> chargeIds);

    Optional<AccountCharge> findByTenantIdAndChargeId(UUID tenantId, UUID chargeId);

    boolean existsByTenantIdAndNameIgnoreCase(UUID tenantId, String name);
}
