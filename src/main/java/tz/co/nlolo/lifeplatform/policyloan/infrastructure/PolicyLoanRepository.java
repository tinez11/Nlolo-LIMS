package tz.co.nlolo.lifeplatform.policyloan.infrastructure;

import tz.co.nlolo.lifeplatform.policyloan.domain.PolicyLoan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PolicyLoanRepository extends JpaRepository<PolicyLoan, UUID> {
    Optional<PolicyLoan> findByLoanIdAndTenantId(UUID loanId, UUID tenantId);
    List<PolicyLoan> findByPolicyNumberAndTenantId(String policyNumber, UUID tenantId);
}
