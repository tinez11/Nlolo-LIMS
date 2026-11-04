package tz.co.nlolo.lifeplatform.billing.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.billing.domain.DepositReversal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DepositReversalRepository extends JpaRepository<DepositReversal, UUID> {

    Optional<DepositReversal> findByReversalIdAndTenantId(UUID reversalId, UUID tenantId);

    List<DepositReversal> findByTenantIdAndStatusOrderByRequestedAtAsc(UUID tenantId, String status);

    /** A reversal open or done on this deposit -- at most one, by ux_deposit_reversal_live. */
    boolean existsByTenantIdAndPaymentIdAndStatusIn(UUID tenantId, UUID paymentId, Collection<String> statuses);
}
