package tz.co.nlolo.lifeplatform.product.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.product.domain.VersionAccountCharge;

import java.util.List;
import java.util.UUID;

public interface VersionAccountChargeRepository extends JpaRepository<VersionAccountCharge, VersionAccountCharge.Key> {
    List<VersionAccountCharge> findByProductVersionIdOrderByPosition(UUID productVersionId);
}
