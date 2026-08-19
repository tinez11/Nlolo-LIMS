package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import tz.co.nlolo.lifeplatform.finaccounting.domain.JournalEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {
    Optional<JournalEntry> findByJournalEntryIdAndTenantId(UUID journalEntryId, UUID tenantId);
    List<JournalEntry> findByTenantIdOrderByPostedAtDesc(UUID tenantId);
    List<JournalEntry> findByTenantIdAndPeriodOrderByPostedAtDesc(UUID tenantId, String period);
    List<JournalEntry> findByTenantIdAndPolicyNumberOrderByPostedAtDesc(UUID tenantId, String policyNumber);
    boolean existsByTenantIdAndSourceEventAndSourceRef(UUID tenantId, String sourceEvent, String sourceRef);
}
