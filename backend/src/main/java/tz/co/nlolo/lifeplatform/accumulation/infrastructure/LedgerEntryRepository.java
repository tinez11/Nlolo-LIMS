package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.nlolo.lifeplatform.accumulation.domain.LedgerEntry;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {
    List<LedgerEntry> findByPolicyNumberOrderBySeq(String policyNumber);
    List<LedgerEntry> findByPostingIdOrderBySeq(UUID postingId);
    boolean existsByReversesEntryId(UUID entryId);

    /**
     * What the ledger holds to a date, summed by the database. A statement is reconciled against
     * THIS, not against its own list -- so a grouping or rendering bug that drops or doubles a line
     * cannot reconcile with itself and slip through.
     */
    @Query("select coalesce(sum(e.amount), 0) from LedgerEntry e where e.policyNumber = :policyNumber "
        + "and e.effectiveDate <= :to and e.seq <= :lastSeq")
    BigDecimal sumThrough(@Param("policyNumber") String policyNumber, @Param("to") LocalDate to,
                          @Param("lastSeq") int lastSeq);
}
