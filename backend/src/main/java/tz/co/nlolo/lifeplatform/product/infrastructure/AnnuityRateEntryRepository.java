package tz.co.nlolo.lifeplatform.product.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.product.domain.AnnuityRateEntry;

import java.util.List;
import java.util.UUID;

public interface AnnuityRateEntryRepository extends JpaRepository<AnnuityRateEntry, UUID> {
    List<AnnuityRateEntry> findByAnnuityFormIdOrderByAge(UUID annuityFormId);
}
