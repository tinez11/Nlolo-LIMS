package tz.co.nlolo.lifeplatform.policy.infrastructure;

import tz.co.nlolo.lifeplatform.policy.domain.LoanValueReservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LoanValueReservationRepository extends JpaRepository<LoanValueReservation, UUID> {
    Optional<LoanValueReservation> findByReservationIdAndTenantId(UUID reservationId, UUID tenantId);

    List<LoanValueReservation> findByPolicyNumberAndTenantIdAndStatus(String policyNumber, UUID tenantId, String status);

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
     * availability, so a crashed prior reservation self-heals on next access. */
    @Modifying
    @Query("UPDATE LoanValueReservation r SET r.status = 'EXPIRED' " +
           "WHERE r.policyNumber = :policyNumber AND r.tenantId = :tenantId AND r.status = 'RESERVED' AND r.ttlExpiresAt < :now")
    int expireStaleReservations(String policyNumber, UUID tenantId, Instant now);
}
