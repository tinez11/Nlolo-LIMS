package tz.co.nlolo.lifeplatform.policy.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.policy.domain.AnnuityVestingEntity;

public interface AnnuityVestingRepository extends JpaRepository<AnnuityVestingEntity, String> {}
