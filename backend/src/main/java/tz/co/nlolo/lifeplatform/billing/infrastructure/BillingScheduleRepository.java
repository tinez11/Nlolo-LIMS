package tz.co.nlolo.lifeplatform.billing.infrastructure;

import tz.co.nlolo.lifeplatform.billing.domain.BillingSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BillingScheduleRepository extends JpaRepository<BillingSchedule, UUID> {
    Optional<BillingSchedule> findByPolicyNumberAndTenantIdAndStatus(String policyNumber, UUID tenantId, String status);
    List<BillingSchedule> findByPolicyNumberAndTenantId(String policyNumber, UUID tenantId);

    /**
     * Take the schedule for a roll-forward, holding a write lock on the row for the transaction.
     *
     * <p>This is what makes the roll-forward exactly-once without needing to run in one place: two
     * drain instances reading the same schedule from {@code schedules_due_for_invoicing()} both
     * reach here, but the second blocks until the first commits and then re-reads a
     * {@code nextDueDate} the first has already advanced past the horizon -- so it raises nothing.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT s FROM BillingSchedule s WHERE s.billingScheduleId = :id")
    Optional<BillingSchedule> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);

    /**
     * Schedules running low on pre-created invoices, across every tenant, for InvoiceRollForward.
     *
     * <p>Through {@code billing.schedules_due_for_invoicing()} rather than a derived query, for the
     * reason {@code communication.pending_reminders()} exists: the drain runs on a schedule with no
     * ambient tenant, so an ordinary query returns nothing. The function is SECURITY DEFINER and
     * returns ONLY {@code [billing_schedule_id, tenant_id]}; the drain sets the tenant per row.
     *
     * @return rows of {@code [billing_schedule_id, tenant_id]}.
     */
    @org.springframework.data.jpa.repository.Query(
        value = "SELECT billing_schedule_id, tenant_id FROM billing.schedules_due_for_invoicing(:horizonMonths)",
        nativeQuery = true)
    List<Object[]> findDueForInvoicingAcrossTenants(@org.springframework.data.repository.query.Param("horizonMonths") int horizonMonths);
}
