package tz.co.nlolo.lifeplatform.unitlinked.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.unitlinked.domain.PremiumSplit;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PremiumSplitRepository extends JpaRepository<PremiumSplit, UUID> {

    /** The split in force at {@code at}: the latest that took effect on or before it. */
    Optional<PremiumSplit> findFirstByTenantIdAndPolicyNumberAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
        UUID tenantId, String policyNumber, Instant at);

    List<PremiumSplit> findByTenantIdAndPolicyNumberOrderByEffectiveFromDesc(UUID tenantId, String policyNumber);

    boolean existsByTenantIdAndPolicyNumber(UUID tenantId, String policyNumber);
}
