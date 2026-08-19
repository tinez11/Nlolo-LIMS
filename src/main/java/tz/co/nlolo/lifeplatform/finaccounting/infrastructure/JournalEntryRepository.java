package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import tz.co.nlolo.lifeplatform.finaccounting.domain.JournalEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
