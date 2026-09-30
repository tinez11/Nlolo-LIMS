package tz.co.nlolo.lifeplatform.policy.infrastructure;

import tz.co.nlolo.lifeplatform.policy.domain.PolicyValue;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyValueRepository extends JpaRepository<PolicyValue, String> {
}
