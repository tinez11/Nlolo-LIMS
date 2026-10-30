package tz.co.nlolo.lifeplatform.policy.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.policy.domain.CreditLifeAgreement;

import java.util.List;
import java.util.UUID;

public interface CreditLifeAgreementRepository extends JpaRepository<CreditLifeAgreement, UUID> {

    List<CreditLifeAgreement> findByTenantIdAndPolicyNumberOrderByValidFromAsc(UUID tenantId, String policyNumber);
}
