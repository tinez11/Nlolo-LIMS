package tz.co.nlolo.lifeplatform.omnichannel.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.omnichannel.domain.PortalAccess;

import java.util.Optional;
import java.util.UUID;

public interface PortalAccessRepository extends JpaRepository<PortalAccess, UUID> {

    Optional<PortalAccess> findByTenantIdAndPartyId(UUID tenantId, UUID partyId);
}
