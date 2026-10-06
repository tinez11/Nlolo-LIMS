package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import tz.co.nlolo.lifeplatform.finaccounting.domain.JournalEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * <b>Every list finder here is paged, and none returns a bare {@code List}</b> (M9 final review,
 * finding I3). {@code journal_entry} is an append-only ledger fed by every money-moving event on the
 * platform, so it is the one table on this schema guaranteed to grow without bound -- an unpaged
 * finder on it is a latent outage, not merely untidy. Tests that legitimately want everything pass
 * {@link Pageable#unpaged()} explicitly, which makes the unbounded read a visible choice at the call
 * site rather than the default.
 *
 * <p>The four-argument {@code findByTenantIdAndPeriodAndPolicyNumber...} finder exists so that BOTH
 * filters are applied in the database (finding M8). Filtering one of them in memory after a
 * single-filter query would, once paged, filter within a page and silently return short or empty
 * pages that look like "no such entries".
 */
public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {
    Optional<JournalEntry> findByJournalEntryIdAndTenantId(UUID journalEntryId, UUID tenantId);
    Page<JournalEntry> findByTenantIdOrderByPostedAtDesc(UUID tenantId, Pageable pageable);
    Page<JournalEntry> findByTenantIdAndPeriodOrderByPostedAtDesc(UUID tenantId, String period, Pageable pageable);
    Page<JournalEntry> findByTenantIdAndPolicyNumberOrderByPostedAtDesc(UUID tenantId, String policyNumber, Pageable pageable);
    Page<JournalEntry> findByTenantIdAndPeriodAndPolicyNumberOrderByPostedAtDesc(UUID tenantId, String period,
                                                                                 String policyNumber, Pageable pageable);
    boolean existsByTenantIdAndSourceEventAndSourceRef(UUID tenantId, String sourceEvent, String sourceRef);

    /** The journal an event already posted, if any: posting is idempotent on (event, source ref). */
    Optional<JournalEntry> findByTenantIdAndSourceEventAndSourceRef(UUID tenantId, String sourceEvent, String sourceRef);

    /**
     * The three-dimension search, replacing the branch-per-combination the four derived finders
     * above were used for.
     *
     * <p>{@code accountCode} is the third dimension, and it is what made a single null-safe
     * predicate worth having: two optional filters are four branches, three are eight, and each
     * branch is a place for one of them to be quietly dropped. Same reasoning
     * {@code PolicyRepository} records for its own multi-filter search.
     *
     * <p>The account filter is an EXISTS over the entry's legs rather than a join, so an entry is
     * returned ONCE however many of its legs hit the account. A join would return a two-legged
     * entry twice whenever both legs touched the same account, and paging duplicates is how a
     * page silently goes short.
     *
     * <p>The legs are matched on tenant as well as entry id: {@code gl_posting} is partitioned and
     * RLS-protected, and a predicate that leans on the join key alone would be correct only by
     * accident.
     */
    @Query("""
        select e from JournalEntry e
         where e.tenantId = :tenantId
           and (:period is null or e.period = :period)
           and (:policyNumber is null or e.policyNumber = :policyNumber)
           and (:accountCode is null or exists (
                 select 1 from GlPosting p
                  where p.journalEntryId = e.journalEntryId
                    and p.tenantId = e.tenantId
                    and p.accountCode = :accountCode))
         order by e.postedAt desc
        """)
    Page<JournalEntry> search(@Param("tenantId") UUID tenantId,
                              @Param("period") String period,
                              @Param("policyNumber") String policyNumber,
                              @Param("accountCode") String accountCode,
                              Pageable pageable);
}
