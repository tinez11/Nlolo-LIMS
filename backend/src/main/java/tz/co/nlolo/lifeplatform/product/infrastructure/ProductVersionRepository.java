package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.domain.ProductVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductVersionRepository extends JpaRepository<ProductVersion, UUID> {
    List<ProductVersion> findByTenantIdAndProductIdOrderByEffectiveDateDesc(UUID tenantId, UUID productId);

    /** Every version of a product, the most recently published first: the product page's version list. */
    List<ProductVersion> findByTenantIdAndProductIdOrderByCreatedAtDesc(UUID tenantId, UUID productId);

    // Used by publishVersion to roll over the prior active-for-new-business version before
    // inserting a new one -- ux_product_version_active permits at most one true row per
    // product_id. A List (not Optional) because the invariant is enforced by publishVersion
    // itself, not assumed here; in the steady state this returns 0 or 1 rows.
    List<ProductVersion> findByTenantIdAndProductIdAndActiveForNewBusinessTrue(UUID tenantId, UUID productId);

    /**
     * The soonest version that has not started yet — the "comes into force on" half of
     * {@code NoActiveProductVersionException}'s message.
     *
     * <p>Only ever read to explain an absence, never to price or to quote: resolving a
     * future version as though it were current is precisely the mispricing
     * {@code findActiveAsOf}'s date bounds exist to prevent.
     */
    Optional<ProductVersion> findFirstByTenantIdAndProductIdAndEffectiveDateAfterOrderByEffectiveDateAsc(
        UUID tenantId, UUID productId, LocalDate asOfDate);

    @org.springframework.data.jpa.repository.Query(
        "SELECT v FROM ProductVersion v WHERE v.tenantId = :tenantId AND v.productId = :productId " +
        "AND v.effectiveDate <= :asOfDate AND (v.retirementDate IS NULL OR v.retirementDate > :asOfDate) " +
        // The later-published version wins a tie on effective date (2026-10-08): two versions published the same
        // day -- a mistake and its correction -- were otherwise in whatever order the database returned them.
        "ORDER BY v.effectiveDate DESC, v.createdAt DESC")
    List<ProductVersion> findActiveAsOf(UUID tenantId, UUID productId, LocalDate asOfDate);
}
