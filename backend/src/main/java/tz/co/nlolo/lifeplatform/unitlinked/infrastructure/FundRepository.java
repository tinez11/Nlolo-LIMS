package tz.co.nlolo.lifeplatform.unitlinked.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.unitlinked.domain.Fund;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every finder takes the tenant: tests connect as the table owner, so RLS alone would not scope them. */
public interface FundRepository extends JpaRepository<Fund, UUID> {

    Optional<Fund> findByTenantIdAndCode(UUID tenantId, String code);

    Optional<Fund> findByTenantIdAndFundId(UUID tenantId, UUID fundId);

    List<Fund> findByTenantIdOrderByCode(UUID tenantId);
}
