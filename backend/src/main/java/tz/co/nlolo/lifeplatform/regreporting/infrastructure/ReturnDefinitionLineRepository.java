package tz.co.nlolo.lifeplatform.regreporting.infrastructure;

import tz.co.nlolo.lifeplatform.regreporting.domain.ReturnDefinitionLine;
import tz.co.nlolo.lifeplatform.regreporting.domain.ReturnDefinitionLineId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReturnDefinitionLineRepository extends JpaRepository<ReturnDefinitionLine, ReturnDefinitionLineId> {
    List<ReturnDefinitionLine> findByTenantIdAndReturnTypeOrderByLineNoAsc(UUID tenantId, String returnType);
}
