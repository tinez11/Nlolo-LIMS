package tz.co.nlolo.lifeplatform.regreporting.infrastructure;

import tz.co.nlolo.lifeplatform.regreporting.domain.ReturnDefinition;
import tz.co.nlolo.lifeplatform.regreporting.domain.ReturnDefinitionId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ReturnDefinitionRepository extends JpaRepository<ReturnDefinition, ReturnDefinitionId> {
    Optional<ReturnDefinition> findByTenantIdAndReturnType(UUID tenantId, String returnType);
}
