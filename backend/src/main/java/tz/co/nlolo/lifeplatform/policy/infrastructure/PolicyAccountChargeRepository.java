package tz.co.nlolo.lifeplatform.policy.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.nlolo.lifeplatform.policy.domain.PolicyAccountCharge;

import java.util.List;
import java.util.UUID;

public interface PolicyAccountChargeRepository extends JpaRepository<PolicyAccountCharge, PolicyAccountCharge.Key> {

    @Query("select c from PolicyAccountCharge c where c.key.policyNumber = :policyNumber and c.tenantId = :tenantId")
    List<PolicyAccountCharge> forPolicy(@Param("tenantId") UUID tenantId, @Param("policyNumber") String policyNumber);
}
