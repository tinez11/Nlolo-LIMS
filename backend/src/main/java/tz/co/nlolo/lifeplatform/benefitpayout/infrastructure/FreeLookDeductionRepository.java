package tz.co.nlolo.lifeplatform.benefitpayout.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.benefitpayout.domain.FreeLookDeduction;

import java.util.List;
import java.util.UUID;

public interface FreeLookDeductionRepository extends JpaRepository<FreeLookDeduction, UUID> {

    List<FreeLookDeduction> findByCancellationId(UUID cancellationId);
}
