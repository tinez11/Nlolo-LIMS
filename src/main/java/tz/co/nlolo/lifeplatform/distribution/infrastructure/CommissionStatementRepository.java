package tz.co.nlolo.lifeplatform.distribution.infrastructure;

import tz.co.nlolo.lifeplatform.distribution.domain.CommissionStatement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every method is tenant-scoped -- RLS is the backstop, not the only guard. */
public interface CommissionStatementRepository extends JpaRepository<CommissionStatement, UUID> {
    Optional<CommissionStatement> findByStatementIdAndTenantId(UUID statementId, UUID tenantId);
    Optional<CommissionStatement> findByTenantIdAndAgentIdAndPeriodAndTotalCurrency(
        UUID tenantId, UUID agentId, String period, String totalCurrency);
    List<CommissionStatement> findByTenantIdAndAgentIdOrderByPeriodDesc(UUID tenantId, UUID agentId);
}
