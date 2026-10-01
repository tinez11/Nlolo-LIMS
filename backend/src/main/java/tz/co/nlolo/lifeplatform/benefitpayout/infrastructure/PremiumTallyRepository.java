package tz.co.nlolo.lifeplatform.benefitpayout.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.benefitpayout.domain.PremiumTally;

public interface PremiumTallyRepository extends JpaRepository<PremiumTally, String> {}
