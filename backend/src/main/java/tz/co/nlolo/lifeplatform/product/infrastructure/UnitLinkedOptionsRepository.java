package tz.co.nlolo.lifeplatform.product.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.product.domain.UnitLinkedOptionsEntity;

import java.util.UUID;

public interface UnitLinkedOptionsRepository extends JpaRepository<UnitLinkedOptionsEntity, UUID> {
}
