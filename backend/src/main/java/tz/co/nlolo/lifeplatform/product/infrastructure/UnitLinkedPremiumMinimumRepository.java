package tz.co.nlolo.lifeplatform.product.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.product.domain.UnitLinkedPremiumMinimumEntity;

import java.util.List;
import java.util.UUID;

public interface UnitLinkedPremiumMinimumRepository extends JpaRepository<UnitLinkedPremiumMinimumEntity, UUID> {
    List<UnitLinkedPremiumMinimumEntity> findByProductVersionIdOrderByFrequency(UUID productVersionId);
}
