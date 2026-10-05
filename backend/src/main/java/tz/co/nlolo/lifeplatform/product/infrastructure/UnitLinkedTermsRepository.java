package tz.co.nlolo.lifeplatform.product.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.product.domain.UnitLinkedTermsEntity;

import java.util.UUID;

public interface UnitLinkedTermsRepository extends JpaRepository<UnitLinkedTermsEntity, UUID> {
}
