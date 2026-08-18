package tz.co.nlolo.lifeplatform.reinsurance.infrastructure;

import tz.co.nlolo.lifeplatform.reinsurance.domain.Cession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CessionRepository extends JpaRepository<Cession, UUID> {
    /** Ordered by creation so a caller taking only the first result (recovery -- one treaty per
     * policy) gets a deterministic pick rather than one dependent on database row order. */
    List<Cession> findByTenantIdAndPolicyNumberOrderByCreatedAtAsc(UUID tenantId, String policyNumber);

    /** Keyed on (tenant, policy) only, matching {@code ux_cession_once} (reinsurance/V2 section 10)
     * -- the design is one treaty per policy, so "a cession already exists for this policy" is the
     * real idempotency check, not "for this (policy, treaty) pair". */
    boolean existsByTenantIdAndPolicyNumber(UUID tenantId, String policyNumber);
}
