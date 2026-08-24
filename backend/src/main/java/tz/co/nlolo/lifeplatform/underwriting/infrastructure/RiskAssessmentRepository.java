package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import tz.co.nlolo.lifeplatform.underwriting.domain.RiskAssessment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RiskAssessmentRepository extends JpaRepository<RiskAssessment, UUID> {
    List<RiskAssessment> findByCaseId(UUID caseId);
}
