package tz.co.nlolo.lifeplatform.policy.infrastructure;

import tz.co.nlolo.lifeplatform.policy.domain.Endorsement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EndorsementRepository extends JpaRepository<Endorsement, java.util.UUID> {
    List<Endorsement> findByPolicyNumberOrderByEffectiveDateDesc(String policyNumber);
}
