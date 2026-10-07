package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import tz.co.nlolo.lifeplatform.underwriting.domain.ProposalGroupLife;

import java.util.List;
import java.util.UUID;

/** A group funeral proposal's lives (underwriting V19), in the order the schedule listed them. */
public interface ProposalGroupLifeRepository extends JpaRepository<ProposalGroupLife, UUID> {

    List<ProposalGroupLife> findByTenantIdAndCaseIdOrderByPositionAsc(UUID tenantId, UUID caseId);

    /** A schedule replaced by an uploaded file: the old lives go first. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ProposalGroupLife l where l.tenantId = :tenantId and l.caseId = :caseId")
    void deleteByCase(@org.springframework.data.repository.query.Param("tenantId") UUID tenantId,
                      @org.springframework.data.repository.query.Param("caseId") UUID caseId);
}
