package tz.co.nlolo.lifeplatform.product.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.product.domain.FuneralPlanBenefitEntity;

import java.util.List;
import java.util.UUID;

public interface FuneralPlanBenefitRepository extends JpaRepository<FuneralPlanBenefitEntity, UUID> {
    List<FuneralPlanBenefitEntity> findByProductVersionId(UUID productVersionId);
}
