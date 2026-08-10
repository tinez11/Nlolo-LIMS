package tz.co.nlolo.lifeplatform.billing.infrastructure;

import tz.co.nlolo.lifeplatform.billing.domain.ArrearsCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ArrearsCaseRepository extends JpaRepository<ArrearsCase, UUID> {
    Optional<ArrearsCase> findByInvoiceIdAndTenantIdAndResolvedAtIsNull(UUID invoiceId, UUID tenantId);
    // Java-side sweep entry point (Task 5): open cases whose SQL-maintained dunningLevel has
    // advanced past what the app has already published an event for, scoped to one tenant so
    // this stays RLS-safe when called from an opportunistic per-request sweep.
    List<ArrearsCase> findByTenantIdAndResolvedAtIsNullAndDunningLevelGreaterThanLastNotifiedDunningLevel(UUID tenantId);
}
