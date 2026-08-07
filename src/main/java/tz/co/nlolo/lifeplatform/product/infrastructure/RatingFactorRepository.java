package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.domain.RatingFactor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RatingFactorRepository extends JpaRepository<RatingFactor, UUID> {
    List<RatingFactor> findByProductVersionId(UUID productVersionId);
    List<RatingFactor> findByProductVersionIdAndFactorType(UUID productVersionId, String factorType);
}
