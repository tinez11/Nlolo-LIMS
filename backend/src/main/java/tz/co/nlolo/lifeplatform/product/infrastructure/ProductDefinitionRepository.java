package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.domain.ProductDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProductDefinitionRepository extends JpaRepository<ProductDefinition, UUID> {
    Optional<ProductDefinition> findByTenantIdAndProductCode(UUID tenantId, String productCode);
    /**
     * By id AND tenant, not {@code findById}: a by-id read that omits the tenant relies on
     * row-level security alone to keep tenants apart, and a caller passing another tenant's
     * id then cannot tell "no such product" from "not yours". Both answer 404 here.
     */
    Optional<ProductDefinition> findByTenantIdAndProductId(UUID tenantId, UUID productId);
    java.util.List<ProductDefinition> findByTenantIdAndStatusAndCategory(UUID tenantId, String status, String category);
    java.util.List<ProductDefinition> findByTenantIdAndStatus(UUID tenantId, String status);
}
