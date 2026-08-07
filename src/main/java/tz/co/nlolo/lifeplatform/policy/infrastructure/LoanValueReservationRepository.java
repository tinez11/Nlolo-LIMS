package tz.co.nlolo.lifeplatform.policy.infrastructure;

import tz.co.nlolo.lifeplatform.policy.domain.LoanValueReservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LoanValueReservationRepository extends JpaRepository<LoanValueReservation, UUID> {
    Optional<LoanValueReservation> findByReservationIdAndTenantId(UUID reservationId, UUID tenantId);

    List<LoanValueReservation> findByPolicyNumberAndTenantIdAndStatus(String policyNumber, UUID tenantId, String status);

    /** Opportunistic TTL sweep (Global Constraints/Task 3) -- runs inside the caller's own
     * TenantContext, so it is RLS-safe (sees only that tenant's rows) unlike a true cross-tenant
     * @Scheduled job would be. Called at the start of every reserveLoanValue before computing
     * availability, so a crashed prior reservation self-heals on next access. */
    @Modifying
    @Query("UPDATE LoanValueReservation r SET r.status = 'EXPIRED' " +
           "WHERE r.policyNumber = :policyNumber AND r.tenantId = :tenantId AND r.status = 'RESERVED' AND r.ttlExpiresAt < :now")
    int expireStaleReservations(String policyNumber, UUID tenantId, Instant now);
}
