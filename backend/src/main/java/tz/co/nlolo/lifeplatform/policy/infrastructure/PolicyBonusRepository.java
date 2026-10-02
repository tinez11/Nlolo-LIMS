package tz.co.nlolo.lifeplatform.policy.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.policy.domain.PolicyBonus;

public interface PolicyBonusRepository extends JpaRepository<PolicyBonus, String> {}
