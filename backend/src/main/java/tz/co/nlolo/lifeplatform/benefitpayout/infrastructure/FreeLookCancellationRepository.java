package tz.co.nlolo.lifeplatform.benefitpayout.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.benefitpayout.domain.FreeLookCancellation;

import java.util.Optional;
import java.util.UUID;

public interface FreeLookCancellationRepository extends JpaRepository<FreeLookCancellation, UUID> {

    /** The policy's most recent cancellation in any status, so a second person can find one to approve. */
    Optional<FreeLookCancellation> findFirstByPolicyNumberOrderByRequestedAtDesc(String policyNumber);
}
