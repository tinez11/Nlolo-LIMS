package tz.co.nlolo.lifeplatform.product.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.product.domain.UnitLinkedSurrenderChargeEntity;

import java.util.List;
import java.util.UUID;

public interface UnitLinkedSurrenderChargeRepository extends JpaRepository<UnitLinkedSurrenderChargeEntity, UUID> {
    List<UnitLinkedSurrenderChargeEntity> findByProductVersionIdOrderByFromYear(UUID productVersionId);
}
