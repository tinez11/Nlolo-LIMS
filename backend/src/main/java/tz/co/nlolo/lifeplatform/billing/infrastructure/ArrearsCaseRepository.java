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
}
