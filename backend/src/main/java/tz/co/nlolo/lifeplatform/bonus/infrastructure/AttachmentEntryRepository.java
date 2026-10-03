package tz.co.nlolo.lifeplatform.bonus.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.nlolo.lifeplatform.bonus.domain.AttachmentEntry;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface AttachmentEntryRepository extends JpaRepository<AttachmentEntry, UUID> {
    List<AttachmentEntry> findByPolicyNumberOrderBySeqAsc(String policyNumber);

    /** COMPOUND's base: what was attached strictly BEFORE a valuation date (declarations may land out of order). */
    @Query("select coalesce(sum(e.amount), 0) from AttachmentEntry e where e.policyNumber = :policyNumber and e.effectiveDate < :date")
    BigDecimal sumBefore(@Param("policyNumber") String policyNumber, @Param("date") LocalDate date);

    /** Attached as at an exit date, inclusive. */
    @Query("select coalesce(sum(e.amount), 0) from AttachmentEntry e where e.policyNumber = :policyNumber and e.effectiveDate <= :date")
    BigDecimal sumThrough(@Param("policyNumber") String policyNumber, @Param("date") LocalDate date);

    @Query("select e from AttachmentEntry e where e.policyNumber = :policyNumber and e.entryType = 'REVERSIONARY' "
        + "and not exists (select r from AttachmentEntry r where r.reversesEntryId = e.entryId) order by e.seq")
    List<AttachmentEntry> unreversed(@Param("policyNumber") String policyNumber);
}
