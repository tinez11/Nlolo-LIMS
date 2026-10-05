package tz.co.nlolo.lifeplatform.product.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.product.domain.UnitLinkedAllocationBandEntity;

import java.util.List;
import java.util.UUID;

public interface UnitLinkedAllocationBandRepository extends JpaRepository<UnitLinkedAllocationBandEntity, UUID> {
    List<UnitLinkedAllocationBandEntity> findByProductVersionIdOrderByFromYear(UUID productVersionId);
}
