package tz.co.nlolo.lifeplatform.policyloan.infrastructure;

import tz.co.nlolo.lifeplatform.policyloan.domain.PolicyLoan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PolicyLoanRepository extends JpaRepository<PolicyLoan, UUID> {
    Optional<PolicyLoan> findByLoanIdAndTenantId(UUID loanId, UUID tenantId);
    List<PolicyLoan> findByPolicyNumberAndTenantId(String policyNumber, UUID tenantId);

    /** The forced-lapse review queue the accrual sweep fills. Ordered oldest-flagged first so a
     * caller draining it in pages cannot starve the loans that have waited longest -- the same
     * class of omission a missing ORDER BY caused elsewhere on this platform, where an unordered
     * query silently returned rows in whatever order Postgres found them. Backed by V5's partial
     * index on (tenant_id, forced_lapse_review_due_at). */
    List<PolicyLoan> findByTenantIdAndForcedLapseReviewDueAtIsNotNullOrderByForcedLapseReviewDueAtAsc(UUID tenantId);
}
