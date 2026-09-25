package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import tz.co.nlolo.lifeplatform.underwriting.domain.RiskAssessment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface RiskAssessmentRepository extends JpaRepository<RiskAssessment, UUID> {
    List<RiskAssessment> findByCaseId(UUID caseId);

    /**
     * How much evidence a case has, for {@code decide}'s "there is nothing to decide on" guard.
     *
     * <p>Tenant-scoped, unlike {@link #findByCaseId} above: a count that omits the tenant leans
     * on row-level security alone to keep tenants apart, and this one gates whether a contract
     * may be issued.
     */
    long countByTenantIdAndCaseId(UUID tenantId, UUID caseId);

    /** Separation of duties: did this person record any of the case's evidence? */
    boolean existsByTenantIdAndCaseIdAndAssessor(UUID tenantId, UUID caseId, String assessor);

    /** Everyone who recorded evidence on the case, for the view's separation-of-duties field. */
    @Query("SELECT DISTINCT r.assessor FROM RiskAssessment r WHERE r.tenantId = :tenantId AND r.caseId = :caseId "
        + "AND r.assessor IS NOT NULL ORDER BY r.assessor")
    List<String> findDistinctAssessors(@Param("tenantId") UUID tenantId, @Param("caseId") UUID caseId);
}
