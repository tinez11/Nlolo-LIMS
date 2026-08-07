package tz.co.nlolo.lifeplatform.policy.infrastructure;

import tz.co.nlolo.lifeplatform.policy.domain.PolicyAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface PolicyAccountRepository extends JpaRepository<PolicyAccount, String> {
    /** PESSIMISTIC_WRITE is the Module-Architecture-B1 fix's actual lock -- it serializes
     * concurrent reserveLoanValue calls against the SAME policy_account row within policy's
     * own transaction (never held across a module boundary), closing the check-then-act race
     * two concurrent loan requests would otherwise hit. See Task 3. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT pa FROM PolicyAccount pa WHERE pa.policyNumber = :policyNumber")
    Optional<PolicyAccount> lockByPolicyNumber(String policyNumber);
}
