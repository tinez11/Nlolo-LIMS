package tz.co.nlolo.lifeplatform.billing.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.nlolo.lifeplatform.billing.domain.BalanceEntry;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface BalanceEntryRepository extends JpaRepository<BalanceEntry, UUID> {

    /**
     * The balance: money in (deposits, premiums reopened by a reversal) less money out (premiums paid, withdrawals,
     * reversals). Zero for a policy with no entries; below zero only when a reversed deposit had already been withdrawn.
     */
    @Query("SELECT COALESCE(SUM(CASE WHEN e.entryType IN ('DEPOSIT', 'PREMIUM_REOPENED') THEN e.amount ELSE -e.amount END), 0) "
        + "FROM BalanceEntry e WHERE e.tenantId = :tenantId AND e.policyNumber = :policyNumber")
    BigDecimal balanceOf(@Param("tenantId") UUID tenantId, @Param("policyNumber") String policyNumber);

    /** Every policy balance in the tenant, summed -- what account 2128 should hold (money movement step 7). */
    @Query("SELECT COALESCE(SUM(CASE WHEN e.entryType IN ('DEPOSIT', 'PREMIUM_REOPENED') THEN e.amount ELSE -e.amount END), 0) "
        + "FROM BalanceEntry e WHERE e.tenantId = :tenantId")
    BigDecimal totalOfTenant(@Param("tenantId") UUID tenantId);

    /** A redelivered confirmation is the same payment: it deposits once. */
    boolean existsByTenantIdAndEntryTypeAndPaymentId(UUID tenantId, String entryType, UUID paymentId);

    List<BalanceEntry> findByTenantIdAndPolicyNumberOrderByCreatedAtAsc(UUID tenantId, String policyNumber);

    /** The deposit a confirmed payment made -- what a reversal takes back. */
    java.util.Optional<BalanceEntry> findByTenantIdAndEntryTypeAndPaymentId(UUID tenantId, String entryType, UUID paymentId);

    /** Entries that undo the given one: a REVERSAL of a deposit, or the PREMIUM_REOPENED parts of a premium paid. */
    List<BalanceEntry> findByTenantIdAndReversesEntryId(UUID tenantId, UUID reversesEntryId);
}
