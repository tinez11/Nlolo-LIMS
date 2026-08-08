package tz.co.nlolo.lifeplatform.policy.infrastructure;

import tz.co.nlolo.lifeplatform.policy.domain.LoanValueReservation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface LoanValueReservationRepository extends JpaRepository<LoanValueReservation, UUID> {
    Optional<LoanValueReservation> findByReservationIdAndTenantId(UUID reservationId, UUID tenantId);

    /** Task 3 review fix (Critical finding #1): confirmReservation/releaseReservation must not
     * do an unlocked read-then-write on status -- a concurrent opportunistic TTL sweep
     * (expireStaleReservations, above) can flip RESERVED -> EXPIRED between the read and the
     * write, and an unconditional save() would silently overwrite that back to
     * CONFIRMED/RELEASED (a lost update resurrecting a hold the system already retired).
     * PESSIMISTIC_WRITE mirrors PolicyAccountRepository.lockByPolicyNumber's existing pattern
     * (same lock type, same "hold until this transaction commits" semantics) rather than
     * introducing a second concurrency-control mechanism (e.g. @Version) into this codebase.
     * Lock is always acquired on the reservation row BEFORE any policy_account lock in the same
     * transaction (confirmReservation's order is unchanged by this fix; the sweep's UPDATE takes
     * its own row lock before its later lockByPolicyNumber call) -- keeping that ordering
     * consistent across both call paths avoids introducing a new deadlock class. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM LoanValueReservation r WHERE r.reservationId = :reservationId AND r.tenantId = :tenantId")
    Optional<LoanValueReservation> lockByReservationIdAndTenantId(UUID reservationId, UUID tenantId);

    /** Brief's top-level "Produces" contract (Task 2/3 build PolicyApiImpl.reserveLoanValue's
     * availability check on top of this). Native aggregate, COALESCE'd to 0 so a policy with
     * zero RESERVED reservations returns BigDecimal.ZERO rather than null -- a bare SUM(amount)
     * over no rows is SQL NULL, which would NPE the very next arithmetic step
     * (PolicyAccount.availableLoanValue(currentlyReserved) unconditionally calls .subtract on
     * its argument). Scoped to :tenantId so this can never sum across tenants even though RLS
     * would also block it. */
    @Query(value = "SELECT COALESCE(SUM(amount), 0) FROM policy.loan_value_reservation " +
           "WHERE policy_number = :policyNumber AND tenant_id = :tenantId AND status = 'RESERVED'",
           nativeQuery = true)
    BigDecimal sumReservedAmountForPolicy(String policyNumber, UUID tenantId);

    /** Opportunistic TTL sweep (Global Constraints/Task 3) -- runs inside the caller's own
     * TenantContext, so it is RLS-safe (sees only that tenant's rows) unlike a true cross-tenant
     * @Scheduled job would be. Called at the start of every reserveLoanValue before computing
     * availability, so a crashed prior reservation self-heals on next access.
     * @Transactional (propagation REQUIRED, the default) so this is safe to call standalone --
     * as the concurrency test does, from a bare worker thread with no surrounding transaction --
     * without throwing TransactionRequiredException; when reserveLoanValue calls it, it simply
     * joins that already-open transaction instead of starting a second one. */
    @Transactional
    @Modifying
    @Query("UPDATE LoanValueReservation r SET r.status = 'EXPIRED' " +
           "WHERE r.policyNumber = :policyNumber AND r.tenantId = :tenantId AND r.status = 'RESERVED' AND r.ttlExpiresAt < :now")
    int expireStaleReservations(String policyNumber, UUID tenantId, Instant now);
}
