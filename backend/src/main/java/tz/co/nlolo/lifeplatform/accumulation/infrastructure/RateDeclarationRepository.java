package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.accumulation.domain.RateDeclaration;

import java.util.List;
import java.util.UUID;

public interface RateDeclarationRepository extends JpaRepository<RateDeclaration, UUID> {
    List<RateDeclaration> findByProductIdOrderByEffectiveFromDescProposedAtDesc(UUID productId);
    List<RateDeclaration> findByProductIdAndStatusOrderByEffectiveFrom(UUID productId, String status);
}
