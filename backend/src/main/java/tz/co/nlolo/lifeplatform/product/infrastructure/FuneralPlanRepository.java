package tz.co.nlolo.lifeplatform.product.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.product.domain.FuneralPlanEntity;

import java.util.List;
import java.util.UUID;

public interface FuneralPlanRepository extends JpaRepository<FuneralPlanEntity, UUID> {
    List<FuneralPlanEntity> findByProductVersionIdOrderByPlanCode(UUID productVersionId);
}
