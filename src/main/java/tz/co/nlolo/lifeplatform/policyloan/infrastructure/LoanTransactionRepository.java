package tz.co.nlolo.lifeplatform.policyloan.infrastructure;

import tz.co.nlolo.lifeplatform.policyloan.domain.LoanTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LoanTransactionRepository extends JpaRepository<LoanTransaction, LoanTransaction.LoanTransactionId> {
    List<LoanTransaction> findByLoanIdOrderByOccurredAt(UUID loanId);
}
