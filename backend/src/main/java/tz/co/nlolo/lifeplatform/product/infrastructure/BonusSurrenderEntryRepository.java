package tz.co.nlolo.lifeplatform.product.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.product.domain.BonusSurrenderEntry;

import java.util.List;
import java.util.UUID;

public interface BonusSurrenderEntryRepository extends JpaRepository<BonusSurrenderEntry, UUID> {
    List<BonusSurrenderEntry> findByProductVersionIdOrderByFromCompletedYears(UUID productVersionId);
}
