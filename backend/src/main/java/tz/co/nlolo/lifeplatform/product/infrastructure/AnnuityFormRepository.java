package tz.co.nlolo.lifeplatform.product.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.product.domain.AnnuityFormEntity;

import java.util.List;
import java.util.UUID;

public interface AnnuityFormRepository extends JpaRepository<AnnuityFormEntity, UUID> {
    List<AnnuityFormEntity> findByProductVersionIdOrderByFormCode(UUID productVersionId);
}
