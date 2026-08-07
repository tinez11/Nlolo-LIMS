package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.domain.BenefitScheduleEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BenefitScheduleEntryRepository extends JpaRepository<BenefitScheduleEntry, UUID> {
    List<BenefitScheduleEntry> findByProductVersionId(UUID productVersionId);
}
