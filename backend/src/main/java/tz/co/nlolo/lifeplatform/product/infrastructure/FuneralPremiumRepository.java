package tz.co.nlolo.lifeplatform.product.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.product.domain.FuneralPremiumEntity;

import java.util.List;
import java.util.UUID;

public interface FuneralPremiumRepository extends JpaRepository<FuneralPremiumEntity, UUID> {
    List<FuneralPremiumEntity> findByProductVersionIdOrderByPlanCodeAscRoleAscAgeFromAsc(UUID productVersionId);
}
