package tz.co.nlolo.lifeplatform.party.infrastructure;

import tz.co.nlolo.lifeplatform.party.api.KycStatus;
import tz.co.nlolo.lifeplatform.party.domain.Party;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PartyRepository extends JpaRepository<Party, UUID> {
    Optional<Party> findByTenantIdAndRegistrationNumber(UUID tenantId, String registrationNumber);

    /** Four combinations of two optional filters -- same shape as PolicyApiImpl.searchPolicies's
     *  own branching, kept as plain derived methods (not a null-safe JPQL query) since two
     *  dimensions is still small enough to enumerate directly. */
    Page<Party> findByTenantId(UUID tenantId, Pageable pageable);
    Page<Party> findByTenantIdAndKycStatus(UUID tenantId, KycStatus kycStatus, Pageable pageable);
    Page<Party> findByTenantIdAndCreatedBy(UUID tenantId, String createdBy, Pageable pageable);
    Page<Party> findByTenantIdAndKycStatusAndCreatedBy(UUID tenantId, KycStatus kycStatus, String createdBy, Pageable pageable);
}
