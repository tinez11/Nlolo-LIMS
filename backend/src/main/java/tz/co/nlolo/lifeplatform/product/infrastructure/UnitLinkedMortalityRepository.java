package tz.co.nlolo.lifeplatform.product.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.product.domain.UnitLinkedMortalityEntity;

import java.util.List;
import java.util.UUID;

public interface UnitLinkedMortalityRepository extends JpaRepository<UnitLinkedMortalityEntity, UUID> {
    List<UnitLinkedMortalityEntity> findByProductVersionIdOrderBySexAscAgeFromAsc(UUID productVersionId);
}
