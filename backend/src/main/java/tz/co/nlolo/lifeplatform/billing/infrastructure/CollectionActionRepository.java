package tz.co.nlolo.lifeplatform.billing.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.billing.domain.CollectionAction;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CollectionActionRepository extends JpaRepository<CollectionAction, UUID> {

    /** What was done on these premiums, newest first (premiums to follow up, 2026-11-11). */
    List<CollectionAction> findByTenantIdAndInvoiceIdInOrderByRecordedAtDesc(UUID tenantId, Collection<UUID> invoiceIds);

    /** A reminder already sent on this premium since the given instant -- one a day (2026-11-11). */
    boolean existsByTenantIdAndInvoiceIdAndKindAndRecordedAtAfter(UUID tenantId, UUID invoiceId, String kind,
                                                                 Instant since);
}
