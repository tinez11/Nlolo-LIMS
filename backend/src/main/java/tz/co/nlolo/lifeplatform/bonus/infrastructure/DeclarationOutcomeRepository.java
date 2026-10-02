package tz.co.nlolo.lifeplatform.bonus.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.bonus.domain.DeclarationOutcome;

import java.util.List;
import java.util.UUID;

public interface DeclarationOutcomeRepository extends JpaRepository<DeclarationOutcome, UUID> {
    List<DeclarationOutcome> findByPolicyNumberOrderByDecidedAtDesc(String policyNumber);
}
