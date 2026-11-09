package tz.co.nlolo.lifeplatform.omnichannel.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.omnichannel.domain.PublicApplication;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PublicApplicationRepository extends JpaRepository<PublicApplication, UUID> {

    Optional<PublicApplication> findByTenantIdAndCaseId(UUID tenantId, UUID caseId);

    List<PublicApplication> findByTenantIdAndPhoneNumber(UUID tenantId, String phoneNumber);
}
