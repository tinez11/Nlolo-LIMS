package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import tz.co.nlolo.lifeplatform.accumulation.domain.DepositPeriod;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DepositPeriodRepository extends JpaRepository<DepositPeriod, UUID> {

    Optional<DepositPeriod> findByPolicyNumberAndStatus(String policyNumber, String status);

    List<DepositPeriod> findByPolicyNumberOrderBySeq(String policyNumber);

    @Query(value = "SELECT policy_number, tenant_id FROM accumulation.deposits_due()", nativeQuery = true)
    List<Object[]> findDueAcrossTenants();

    /** Every period that ended in a given status, oldest closing first -- the awaiting-payee list filters these. */
    List<DepositPeriod> findByStatusOrderByClosedOn(String status);
}
