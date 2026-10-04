package tz.co.nlolo.lifeplatform.product.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.product.domain.FuneralRoleRuleEntity;

import java.util.List;
import java.util.UUID;

public interface FuneralRoleRuleRepository extends JpaRepository<FuneralRoleRuleEntity, UUID> {
    List<FuneralRoleRuleEntity> findByProductVersionId(UUID productVersionId);
}
