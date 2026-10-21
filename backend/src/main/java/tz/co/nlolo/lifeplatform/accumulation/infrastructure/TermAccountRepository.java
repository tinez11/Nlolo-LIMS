package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import tz.co.nlolo.lifeplatform.accumulation.domain.TermAccount;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TermAccountRepository extends JpaRepository<TermAccount, UUID> {

    Optional<TermAccount> findByPolicyNumber(String policyNumber);

    @Query(value = "SELECT policy_number, tenant_id FROM accumulation.term_accounts_due()", nativeQuery = true)
    List<Object[]> findDueAcrossTenants();

    /** Every term account in a status, oldest maturity first -- the awaiting-payee list reads these. */
    List<TermAccount> findByStatusOrderByMaturedOn(String status);
}
