package tz.co.nlolo.lifeplatform.billing.infrastructure;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.nlolo.lifeplatform.billing.domain.PolicyBalance;

import java.util.Optional;
import java.util.UUID;

public interface PolicyBalanceRepository extends JpaRepository<PolicyBalance, PolicyBalance.Key> {

    /**
     * Opens the policy's balance row if it has none. ON CONFLICT DO NOTHING rather than a read-then-insert, so two
     * first deposits at once cannot both insert -- the loser's statement simply finds the row there.
     */
    @Modifying
    @Query(value = "INSERT INTO billing.policy_balance (tenant_id, policy_number, currency) "
        + "VALUES (:tenantId, :policyNumber, :currency) ON CONFLICT (tenant_id, policy_number) DO NOTHING",
        nativeQuery = true)
    int open(@Param("tenantId") UUID tenantId, @Param("policyNumber") String policyNumber,
             @Param("currency") String currency);

    /**
     * Takes the balance for allocation, holding a write lock on the row for the transaction: a second deposit to the
     * same policy waits here until the first has paid what it pays, then sees the balance that left.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM PolicyBalance b WHERE b.tenantId = :tenantId AND b.policyNumber = :policyNumber")
    Optional<PolicyBalance> findForUpdate(@Param("tenantId") UUID tenantId, @Param("policyNumber") String policyNumber);
}
