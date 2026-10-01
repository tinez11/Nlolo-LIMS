package tz.co.nlolo.lifeplatform.benefitpayout.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.benefitpayout.domain.PayoutStream;

import java.util.List;
import java.util.UUID;

public interface PayoutStreamRepository extends JpaRepository<PayoutStream, UUID> {

    List<PayoutStream> findByPolicyNumber(String policyNumber);
}
