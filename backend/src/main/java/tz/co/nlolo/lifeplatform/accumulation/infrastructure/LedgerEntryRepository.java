package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.accumulation.domain.LedgerEntry;

import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {
    List<LedgerEntry> findByPolicyNumberOrderBySeq(String policyNumber);
    List<LedgerEntry> findByPostingIdOrderBySeq(UUID postingId);
    boolean existsByReversesEntryId(UUID entryId);
}
