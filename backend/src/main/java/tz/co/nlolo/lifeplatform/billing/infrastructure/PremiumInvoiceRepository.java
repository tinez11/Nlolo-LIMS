package tz.co.nlolo.lifeplatform.billing.infrastructure;

import tz.co.nlolo.lifeplatform.billing.domain.PremiumInvoice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PremiumInvoiceRepository extends JpaRepository<PremiumInvoice, PremiumInvoice.PremiumInvoiceId> {
    List<PremiumInvoice> findByPolicyNumberAndTenantIdOrderByDueDate(String policyNumber, UUID tenantId);
    List<PremiumInvoice> findByPolicyNumberAndTenantIdAndStatusIn(String policyNumber, UUID tenantId, List<String> statuses);
    Optional<PremiumInvoice> findFirstByPolicyNumberAndTenantIdAndStatusInOrderByDueDateAsc(
        String policyNumber, UUID tenantId, List<String> statuses);
    Optional<PremiumInvoice> findByInvoiceIdAndTenantId(UUID invoiceId, UUID tenantId);

    /**
     * The invoices behind one page of arrears cases, fetched together. An arrears case holds an
     * invoiceId and no money, so the collections queue has to resolve amounts -- one query for
     * the page, never one per row. Callers must not pass an empty collection.
     */
    List<PremiumInvoice> findByTenantIdAndInvoiceIdIn(UUID tenantId, Collection<UUID> invoiceIds);
    List<PremiumInvoice> findByBillingScheduleIdAndTenantIdOrderByDueDateDesc(UUID billingScheduleId, UUID tenantId);
}
