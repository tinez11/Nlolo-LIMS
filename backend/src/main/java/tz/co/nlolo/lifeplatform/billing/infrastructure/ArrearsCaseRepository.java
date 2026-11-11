package tz.co.nlolo.lifeplatform.billing.infrastructure;

import tz.co.nlolo.lifeplatform.billing.domain.ArrearsCase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ArrearsCaseRepository extends JpaRepository<ArrearsCase, UUID> {
    Optional<ArrearsCase> findByInvoiceIdAndTenantIdAndResolvedAtIsNull(UUID invoiceId, UUID tenantId);

    /**
     * Every open arrears case for a set of invoices, so a list of invoices resolves its dunning
     * levels in ONE query instead of one per row. Callers must not pass an empty collection --
     * an empty {@code IN} is a Postgres syntax error; {@code listInvoices} returns early instead.
     */
    List<ArrearsCase> findByTenantIdAndInvoiceIdInAndResolvedAtIsNull(UUID tenantId, Collection<UUID> invoiceIds);

    // Java-side sweep entry point (Task 5): open cases whose SQL-maintained dunningLevel has
    // advanced past what the app has already published an event for, scoped to one tenant so
    // this stays RLS-safe when called from an opportunistic per-request sweep. Derived-query
    // naming can only compare a property against a method parameter, not against another
    // property on the same entity, so this comparison (dunningLevel > lastNotifiedDunningLevel)
    // needs an explicit @Query.
    @Query("select a from ArrearsCase a where a.tenantId = :tenantId and a.resolvedAt is null " +
           "and a.dunningLevel > a.lastNotifiedDunningLevel")
    List<ArrearsCase> findByTenantIdAndResolvedAtIsNullAndDunningLevelGreaterThanLastNotifiedDunningLevel(@Param("tenantId") UUID tenantId);

    /**
     * The collections queue: one page of this tenant's arrears cases, newest escalation first.
     *
     * <p>Null-safe on both filters, the same shape {@code PartyRepository.search} uses.
     * {@code minDunningLevel} is a floor rather than an exact match because the question a
     * collections officer asks is "what is at level 3 or worse", not "what is at exactly 3" --
     * an exact filter would hide the level-5 cases from somebody triaging level 3 and up.
     *
     * <p>{@code resolved} splits open work from history: null for both, false for the live
     * queue, true for what has already been settled or waived.
     *
     * <p>The caller supplies the sort, and it MUST be total -- see
     * {@code BillingController.listArrears} for why {@code arrearsCaseId} is on the end of it.
     */
    @Query("select a from ArrearsCase a where a.tenantId = :tenantId "
        + "and (:minDunningLevel is null or a.dunningLevel >= :minDunningLevel) "
        + "and (:resolved is null "
        + "     or (:resolved = true and a.resolvedAt is not null) "
        + "     or (:resolved = false and a.resolvedAt is null))")
    Page<ArrearsCase> search(@Param("tenantId") UUID tenantId,
                              @Param("minDunningLevel") Integer minDunningLevel,
                              @Param("resolved") Boolean resolved,
                              Pageable pageable);

    /**
     * What is owed, bucketed by age, per currency (2026-10-15; every unpaid premium since 2026-11-11).
     *
     * <p>Every premium still open -- due, in grace, overdue or part paid -- that fell due on or before {@code asOf}, and
     * any other with an open arrears case. Until 2026-11-11 it counted ONLY invoices with an open arrears case, so a
     * premium past due that the dunning sweep had not yet opened a case on was owed and nowhere in the figure: on dev the
     * finance dashboard read TZS 1.9K owed beside TZS 664K overdue on the follow-up list. A premium not yet due is not
     * owed today, and the twelve months billing creates ahead stay out.
     *
     * <p>Outstanding is charged, less paid, less credited back -- PremiumInvoice.balanceDue -- and a premium that owes
     * nothing is left out. Native: the bucketing is a CASE over date arithmetic.
     *
     * <p>One row per currency, band and POLICY: [currency, bucket, cases, outstanding, policy_number]. Per policy so the
     * caller can leave out an offer -- a policy still PROPOSED, whose first premium is the offer's, not owed -- which
     * billing cannot tell from its own tables (2026-11-11: 14 offers' premiums were TZS 3.29M of the figure on dev).
     *
     * <p>Only buckets with rows come back. The caller fills in the empty ones, because the
     * bands are a promise of the view and "nothing is this old" is an answer worth printing.
     */
    @Query(value = """
        SELECT i.currency AS currency,
               CASE WHEN i.due_date >= :asOf THEN 'NOT_DUE'
                    WHEN :asOf - i.due_date <= 7 THEN 'D1_7'
                    WHEN :asOf - i.due_date <= 14 THEN 'D8_14'
                    WHEN :asOf - i.due_date <= 30 THEN 'D15_30'
                    WHEN :asOf - i.due_date <= 60 THEN 'D31_60'
                    WHEN :asOf - i.due_date <= 90 THEN 'D61_90'
                    ELSE 'OVER_90' END AS bucket,
               COUNT(*) AS cases,
               SUM(i.amount - COALESCE(i.amount_paid, 0) - COALESCE(c.credited, 0)) AS outstanding,
               i.policy_number AS policy_number
          FROM billing.premium_invoice i
          LEFT JOIN (SELECT original_invoice_id, SUM(amount) AS credited
                       FROM billing.premium_credit WHERE tenant_id = :tenantId GROUP BY original_invoice_id) c
            ON c.original_invoice_id = i.invoice_id
         WHERE i.tenant_id = :tenantId
           AND i.status IN ('DUE', 'IN_GRACE', 'OVERDUE', 'PARTIALLY_PAID')
           AND i.amount - COALESCE(i.amount_paid, 0) - COALESCE(c.credited, 0) > 0
           AND (i.due_date <= :asOf
                OR EXISTS (SELECT 1 FROM billing.arrears_case a
                            WHERE a.tenant_id = i.tenant_id AND a.invoice_id = i.invoice_id AND a.resolved_at IS NULL))
         GROUP BY i.currency, bucket, i.policy_number
         ORDER BY i.currency, bucket
        """, nativeQuery = true)
    List<Object[]> receivablesAging(@Param("tenantId") UUID tenantId, @Param("asOf") java.time.LocalDate asOf);
}
