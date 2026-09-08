package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import tz.co.nlolo.lifeplatform.underwriting.domain.RiskAssessment;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
