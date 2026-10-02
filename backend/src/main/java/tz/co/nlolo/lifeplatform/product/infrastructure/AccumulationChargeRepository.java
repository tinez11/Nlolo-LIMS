package tz.co.nlolo.lifeplatform.product.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.product.domain.AccumulationCharge;

import java.util.List;
import java.util.UUID;

public interface AccumulationChargeRepository extends JpaRepository<AccumulationCharge, UUID> {
    List<AccumulationCharge> findByProductVersionIdOrderByFromPolicyYear(UUID productVersionId);
}
