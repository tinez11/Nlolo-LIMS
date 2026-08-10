package tz.co.nlolo.lifeplatform.policyloan.infrastructure;

import tz.co.nlolo.lifeplatform.policyloan.domain.LoanInterestTerm;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LoanInterestTermRepository extends JpaRepository<LoanInterestTerm, UUID> {
    List<LoanInterestTerm> findByLoanIdOrderByEffectiveFromDesc(UUID loanId);
}
