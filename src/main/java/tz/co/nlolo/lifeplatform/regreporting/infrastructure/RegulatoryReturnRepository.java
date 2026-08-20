package tz.co.nlolo.lifeplatform.regreporting.infrastructure;

import tz.co.nlolo.lifeplatform.regreporting.domain.RegulatoryReturn;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RegulatoryReturnRepository extends JpaRepository<RegulatoryReturn, UUID> {
    /** Scoped by tenant even though {@code returnId} is already globally unique -- callers must
     * never fetch a return outside the caller's own tenant by id alone. */
    Optional<RegulatoryReturn> findByReturnIdAndTenantId(UUID returnId, UUID tenantId);

    Optional<RegulatoryReturn> findByTenantIdAndReturnTypeAndPeriod(UUID tenantId, String returnType, String period);

    List<RegulatoryReturn> findByTenantIdOrderByGeneratedAtDesc(UUID tenantId);

    List<RegulatoryReturn> findByTenantIdAndPeriodOrderByGeneratedAtDesc(UUID tenantId, String period);
}
