package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.domain.ProductVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ProductVersionRepository extends JpaRepository<ProductVersion, UUID> {
    List<ProductVersion> findByTenantIdAndProductIdOrderByEffectiveDateDesc(UUID tenantId, UUID productId);

    // Used by publishVersion to roll over the prior active-for-new-business version before
    // inserting a new one -- ux_product_version_active permits at most one true row per
    // product_id. A List (not Optional) because the invariant is enforced by publishVersion
    // itself, not assumed here; in the steady state this returns 0 or 1 rows.
    List<ProductVersion> findByTenantIdAndProductIdAndActiveForNewBusinessTrue(UUID tenantId, UUID productId);

    @org.springframework.data.jpa.repository.Query(
        "SELECT v FROM ProductVersion v WHERE v.tenantId = :tenantId AND v.productId = :productId " +
        "AND v.effectiveDate <= :asOfDate AND (v.retirementDate IS NULL OR v.retirementDate > :asOfDate) " +
        "ORDER BY v.effectiveDate DESC")
    List<ProductVersion> findActiveAsOf(UUID tenantId, UUID productId, LocalDate asOfDate);
}
