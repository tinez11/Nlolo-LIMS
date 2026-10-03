package tz.co.nlolo.lifeplatform.bonus.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.bonus.domain.BonusRequestKey;

public interface BonusRequestKeyRepository extends JpaRepository<BonusRequestKey, BonusRequestKey.Id> {}
