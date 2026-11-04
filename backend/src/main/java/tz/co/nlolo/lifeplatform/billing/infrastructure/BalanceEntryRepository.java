package tz.co.nlolo.lifeplatform.billing.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.nlolo.lifeplatform.billing.domain.BalanceEntry;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface BalanceEntryRepository extends JpaRepository<BalanceEntry, UUID> {

    /** The balance: every deposit less every premium paid from it. Zero for a policy with no entries. */
    @Query("SELECT COALESCE(SUM(CASE WHEN e.entryType = 'DEPOSIT' THEN e.amount ELSE -e.amount END), 0) "
        + "FROM BalanceEntry e WHERE e.tenantId = :tenantId AND e.policyNumber = :policyNumber")
    BigDecimal balanceOf(@Param("tenantId") UUID tenantId, @Param("policyNumber") String policyNumber);

    /** A redelivered confirmation is the same payment: it deposits once. */
    boolean existsByTenantIdAndEntryTypeAndPaymentId(UUID tenantId, String entryType, UUID paymentId);

    List<BalanceEntry> findByTenantIdAndPolicyNumberOrderByCreatedAtAsc(UUID tenantId, String policyNumber);
}
