package tz.co.nlolo.lifeplatform.product.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.product.domain.AnnuityFrequencyEntry;

import java.util.List;
import java.util.UUID;

public interface AnnuityFrequencyEntryRepository extends JpaRepository<AnnuityFrequencyEntry, UUID> {
    List<AnnuityFrequencyEntry> findByProductVersionId(UUID productVersionId);
}
