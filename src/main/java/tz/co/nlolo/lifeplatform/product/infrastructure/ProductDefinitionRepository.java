package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.domain.ProductDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProductDefinitionRepository extends JpaRepository<ProductDefinition, UUID> {
    Optional<ProductDefinition> findByTenantIdAndProductCode(UUID tenantId, String productCode);
    java.util.List<ProductDefinition> findByTenantIdAndStatusAndCategory(UUID tenantId, String status, String category);
    java.util.List<ProductDefinition> findByTenantIdAndStatus(UUID tenantId, String status);
}
