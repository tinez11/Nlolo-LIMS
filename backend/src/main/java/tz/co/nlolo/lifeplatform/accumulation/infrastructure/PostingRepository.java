package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.accumulation.domain.Posting;

import java.util.Optional;
import java.util.UUID;

public interface PostingRepository extends JpaRepository<Posting, UUID> {
    boolean existsByTenantIdAndSourceTypeAndSourceRef(UUID tenantId, String sourceType, String sourceRef);
    Optional<Posting> findByTenantIdAndSourceTypeAndSourceRef(UUID tenantId, String sourceType, String sourceRef);
}
