package tz.co.nlolo.lifeplatform.unitlinked.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.unitlinked.domain.WithdrawalRequest;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UnitLinkedWithdrawalRequestRepository extends JpaRepository<WithdrawalRequest, UUID> {

    Optional<WithdrawalRequest> findByTenantIdAndWithdrawalId(UUID tenantId, UUID withdrawalId);

    Optional<WithdrawalRequest> findFirstByTenantIdAndPolicyNumberAndStatusIn(UUID tenantId, String policyNumber,
                                                                             Collection<String> statuses);

    List<WithdrawalRequest> findByTenantIdAndPolicyNumberOrderByRequestedAtDesc(UUID tenantId, String policyNumber);
}
