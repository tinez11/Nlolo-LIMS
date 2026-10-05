package tz.co.nlolo.lifeplatform.product.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.product.domain.UnitLinkedFundEntity;

import java.util.List;
import java.util.UUID;

public interface UnitLinkedFundRepository extends JpaRepository<UnitLinkedFundEntity, UUID> {
    List<UnitLinkedFundEntity> findByProductVersionIdOrderByFundCode(UUID productVersionId);
}
