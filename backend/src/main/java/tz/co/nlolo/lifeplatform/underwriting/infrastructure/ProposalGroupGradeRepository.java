package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import tz.co.nlolo.lifeplatform.underwriting.domain.ProposalGroupGrade;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProposalGroupGradeRepository extends JpaRepository<ProposalGroupGrade, UUID> {

    /** A proposal's grade table, oldest first, so the order it was entered in is the order shown. */
    List<ProposalGroupGrade> findByTenantIdAndCaseIdOrderByCreatedAtAsc(UUID tenantId, UUID caseId);
}
