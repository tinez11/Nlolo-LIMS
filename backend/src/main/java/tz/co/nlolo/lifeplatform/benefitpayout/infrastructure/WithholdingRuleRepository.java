package tz.co.nlolo.lifeplatform.benefitpayout.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.benefitpayout.domain.WithholdingRule;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped explicitly, not by RLS alone: a connection as the table owner (every test, and any
 * migration-run job) bypasses RLS, and a rule leaking across tenants would withhold one insurer's
 * tax rate from another's customers.
 */
public interface WithholdingRuleRepository extends JpaRepository<WithholdingRule, UUID> {
    List<WithholdingRule> findByTenantIdOrderByEffectiveFromDescProposedAtDesc(UUID tenantId);

    List<WithholdingRule> findByTenantIdAndStatus(UUID tenantId, String status);

    Optional<WithholdingRule> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    Optional<WithholdingRule> findByRuleIdAndTenantId(UUID ruleId, UUID tenantId);
}
