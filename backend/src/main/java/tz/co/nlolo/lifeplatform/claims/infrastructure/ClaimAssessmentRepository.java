package tz.co.nlolo.lifeplatform.claims.infrastructure;

import tz.co.nlolo.lifeplatform.claims.domain.ClaimAssessment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ClaimAssessmentRepository extends JpaRepository<ClaimAssessment, UUID> {
    List<ClaimAssessment> findByClaimIdAndTenantIdOrderByCreatedAtDesc(UUID claimId, UUID tenantId);

    /** Task 5's "APPROVED requires >=1 assessment" invariant (claims/V1:56-59). */
    long countByClaimIdAndTenantId(UUID claimId, UUID tenantId);

    /** Task 5's separation-of-duties check: the deciding user must not be an assessor of this
     * same claim. Matches on the persisted assessor string, which is what a role claim cannot
     * tell you. */
    boolean existsByClaimIdAndTenantIdAndAssessor(UUID claimId, UUID tenantId, String assessor);
}
