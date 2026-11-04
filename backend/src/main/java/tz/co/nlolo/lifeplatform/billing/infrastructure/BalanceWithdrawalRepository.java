package tz.co.nlolo.lifeplatform.billing.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.billing.domain.BalanceWithdrawal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BalanceWithdrawalRepository extends JpaRepository<BalanceWithdrawal, UUID> {

    Optional<BalanceWithdrawal> findByWithdrawalIdAndTenantId(UUID withdrawalId, UUID tenantId);

    List<BalanceWithdrawal> findByTenantIdAndPolicyNumberOrderByRequestedAtDesc(UUID tenantId, String policyNumber);

    List<BalanceWithdrawal> findByTenantIdAndStatusOrderByRequestedAtAsc(UUID tenantId, String status);

    /** A withdrawal still in flight on this policy -- at most one, by ux_balance_withdrawal_live. */
    boolean existsByTenantIdAndPolicyNumberAndStatusIn(UUID tenantId, String policyNumber, Collection<String> statuses);
}
