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

    /** Every premium in these states falling due on or before a day, oldest first (premiums to follow up). */
    List<PremiumInvoice> findByTenantIdAndStatusInAndDueDateLessThanEqualOrderByDueDateAsc(UUID tenantId,
                                                                                         Collection<String> statuses,
                                                                                         LocalDate dueOnOrBefore);
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

    /**
     * The single-premium invoice raised for one accepted enrolment file.
     *
     * <p>Optional rather than List because ux_premium_invoice_per_submission makes it at most
     * one -- and the redelivery path in raiseSinglePremiumInvoice depends on that being true.
     */
    Optional<PremiumInvoice> findByTenantIdAndEnrolmentSubmissionId(UUID tenantId, UUID enrolmentSubmissionId);

    /**
     * What each policy's invoices still owe in TZS -- PremiumInvoice.balanceDue summed: charged less paid less credited,
     * never below zero, a waived invoice nothing (money movement step 7): [policy, owed].
     */
    @org.springframework.data.jpa.repository.Query(nativeQuery = true, value = """
        SELECT i.policy_number,
               SUM(GREATEST(i.amount - COALESCE(i.amount_paid, 0) - COALESCE(c.credited, 0), 0))
          FROM billing.premium_invoice i
          LEFT JOIN (SELECT original_invoice_id, SUM(amount) AS credited
                       FROM billing.premium_credit WHERE tenant_id = :tenantId GROUP BY original_invoice_id) c
            ON c.original_invoice_id = i.invoice_id
         WHERE i.tenant_id = :tenantId AND i.status <> 'WAIVED' AND i.currency = 'TZS'
         GROUP BY i.policy_number""")
    List<Object[]> owedByPolicy(@org.springframework.data.repository.query.Param("tenantId") UUID tenantId);
}
