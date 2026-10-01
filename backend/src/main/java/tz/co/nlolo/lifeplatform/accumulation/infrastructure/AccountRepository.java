package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.nlolo.lifeplatform.accumulation.domain.Account;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, String> {

    /**
     * SELECT ... FOR UPDATE. Two postings to one account -- a contribution arriving during the
     * month-end run -- must be SERIALISED, not raced: each needs the other's balance as its starting
     * point. A row lock makes the second wait instead of failing an optimistic version check.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.policyNumber = :policyNumber")
    Optional<Account> lockForPosting(@Param("policyNumber") String policyNumber);

    @Query(value = "SELECT policy_number, tenant_id FROM accumulation.accounts_due_month_end()", nativeQuery = true)
    List<Object[]> findDueMonthEndAcrossTenants();

    @Query(value = "SELECT policy_number, tenant_id FROM accumulation.accounts_due_annual_statement()", nativeQuery = true)
    List<Object[]> findDueAnnualStatementAcrossTenants();
}
