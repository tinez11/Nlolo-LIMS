package tz.co.nlolo.lifeplatform.benefitpayout.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.benefitpayout.domain.FreeLookCancellation;

import java.util.Optional;
import java.util.UUID;

public interface FreeLookCancellationRepository extends JpaRepository<FreeLookCancellation, UUID> {

    /** The policy's most recent cancellation in any status, so a second person can find one to approve. */
    Optional<FreeLookCancellation> findFirstByPolicyNumberOrderByRequestedAtDesc(String policyNumber);

    /** Every cancellation in one status, across the tenant, oldest first: the approvals queue (2026-10-27). */
    java.util.List<FreeLookCancellation> findByStatusOrderByRequestedAtAsc(String status);
}
