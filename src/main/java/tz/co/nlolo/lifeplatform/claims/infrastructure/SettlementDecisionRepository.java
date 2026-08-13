package tz.co.nlolo.lifeplatform.claims.infrastructure;

import tz.co.nlolo.lifeplatform.claims.domain.SettlementDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SettlementDecisionRepository extends JpaRepository<SettlementDecision, UUID> {
    List<SettlementDecision> findByClaimIdAndTenantIdOrderByDecidedAtDesc(UUID claimId, UUID tenantId);
}
