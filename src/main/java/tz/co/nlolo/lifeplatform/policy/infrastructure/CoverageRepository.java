package tz.co.nlolo.lifeplatform.policy.infrastructure;

import tz.co.nlolo.lifeplatform.policy.domain.Coverage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CoverageRepository extends JpaRepository<Coverage, UUID> {
    List<Coverage> findByPolicyNumberAndActiveTrue(String policyNumber);
}
