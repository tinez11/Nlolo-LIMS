package tz.co.nlolo.lifeplatform.annuity.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.annuity.domain.VestingInstruction;

import java.util.Optional;
import java.util.UUID;

public interface VestingInstructionRepository extends JpaRepository<VestingInstruction, UUID> {

    /** The policy's current instruction, if staff recorded one. */
    Optional<VestingInstruction> findByPolicyNumberAndCurrentTrue(String policyNumber);
}
