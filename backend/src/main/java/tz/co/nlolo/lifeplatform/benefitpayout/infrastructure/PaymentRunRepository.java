package tz.co.nlolo.lifeplatform.benefitpayout.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import tz.co.nlolo.lifeplatform.benefitpayout.domain.PaymentRun;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRunRepository extends JpaRepository<PaymentRun, UUID> {

    /**
     * The tenant is named rather than left to RLS.
     *
     * <p>Defence in depth, and it matters more here than in most places: if this query ever saw
     * another tenant's row, {@code prepareRun} would find it, decide today's run already exists,
     * and file this tenant's instalments into a batch a different insurer approves. RLS does
     * prevent that in production -- but the application connects as the table owner in several
     * supported configurations, and one of those is every integration test on this platform.
     */
    Optional<PaymentRun> findByTenantIdAndRunDate(UUID tenantId, LocalDate runDate);

    List<PaymentRun> findByTenantIdOrderByRunDateDesc(UUID tenantId);

    /**
     * Across every tenant, ids only -- the drain sets the tenant per row and reads the rest under
     * ordinary RLS. See the function's own comment for why a sweep must see past RLS at all.
     */
    @Query(value = "SELECT tenant_id FROM benefitpayout.tenants_with_stream_instalments_due()", nativeQuery = true)
    List<UUID> findTenantsWithStreamInstalmentsDue();
}
