package tz.co.nlolo.lifeplatform.distribution.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.distribution.domain.AgentLogin;

import java.util.Optional;
import java.util.UUID;

public interface AgentLoginRepository extends JpaRepository<AgentLogin, UUID> {

    Optional<AgentLogin> findByTenantIdAndAgentId(UUID tenantId, UUID agentId);
}
