package tz.co.nlolo.lifeplatform.party.infrastructure;

import tz.co.nlolo.lifeplatform.party.domain.Party;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PartyRepository extends JpaRepository<Party, UUID> {
    Optional<Party> findByTenantIdAndRegistrationNumber(UUID tenantId, String registrationNumber);
}
