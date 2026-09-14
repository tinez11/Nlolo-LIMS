package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import tz.co.nlolo.lifeplatform.finaccounting.domain.GlPosting;
import tz.co.nlolo.lifeplatform.finaccounting.domain.GlPostingId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface GlPostingRepository extends JpaRepository<GlPosting, GlPostingId> {
    List<GlPosting> findByTenantIdAndJournalEntryIdOrderByDirectionAsc(UUID tenantId, UUID journalEntryId);

    /** The batched form of the finder above, for building a page of {@code JournalEntryView}s in TWO
     * queries instead of one-per-entry (M9 final review, finding I3 -- the N+1 half). Ordered by
     * entry then direction so each entry's legs arrive contiguous and CR-before-DR, matching the
     * single-entry finder's ordering exactly; callers group by {@code journalEntryId}. */
    List<GlPosting> findByTenantIdAndJournalEntryIdInOrderByJournalEntryIdAscDirectionAsc(
        UUID tenantId, Collection<UUID> journalEntryIds);

    List<GlPosting> findByTenantIdAndAccountCodeAndPeriod(UUID tenantId, String accountCode, String period);

    /** The real guard behind deleting a chart-of-account row: true once ANY posting has ever
     *  referenced this account, matching {@code fk_gl_posting_account_code}'s own scope
     *  (finaccounting/V3) exactly -- not scoped to a period, unlike the finder above. */
    boolean existsByTenantIdAndAccountCode(UUID tenantId, String accountCode);

    /**
     * DR and CR totals per account -- the aggregation this module never had.
     *
     * <p>9030 postings could sit in the ledger with no way to ask what any account's balance
     * was: {@code ChartOfAccountView} carries structure only, and nothing anywhere summed a
     * posting. A general ledger that cannot state a balance is a list of movements.
     *
     * <p>Grouped by account AND direction, then pivoted by the caller, rather than two
     * {@code sum(case when ...)} expressions. JPQL needs a fully-qualified enum literal inside
     * a CASE, which is both unreadable and easy to get silently wrong; grouping instead keeps
     * the query trivial and returns at most two rows per account.
     *
     * <p>{@code period} null means every period -- an inception-to-date balance. The caller is
     * expected to say which it asked for, because an undated balance on screen is a number
     * nobody can reconcile.
     *
     * <p>Tenant-scoped in its own right; RLS is the backstop, per this platform's convention.
     */
    @Query("""
        select p.accountCode, p.direction, sum(p.amount)
          from GlPosting p
         where p.tenantId = :tenantId
           and (:period is null or p.period = :period)
         group by p.accountCode, p.direction
        """)
    List<Object[]> sumByAccountAndDirection(@Param("tenantId") UUID tenantId,
                                            @Param("period") String period);
}
