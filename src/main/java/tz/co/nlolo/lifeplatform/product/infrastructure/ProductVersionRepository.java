package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.domain.ProductVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ProductVersionRepository extends JpaRepository<ProductVersion, UUID> {
    List<ProductVersion> findByTenantIdAndProductIdOrderByEffectiveDateDesc(UUID tenantId, UUID productId);

    @org.springframework.data.jpa.repository.Query(
        "SELECT v FROM ProductVersion v WHERE v.tenantId = :tenantId AND v.productId = :productId " +
        "AND v.effectiveDate <= :asOfDate AND (v.retirementDate IS NULL OR v.retirementDate > :asOfDate) " +
        "ORDER BY v.effectiveDate DESC")
    List<ProductVersion> findActiveAsOf(UUID tenantId, UUID productId, LocalDate asOfDate);
}
