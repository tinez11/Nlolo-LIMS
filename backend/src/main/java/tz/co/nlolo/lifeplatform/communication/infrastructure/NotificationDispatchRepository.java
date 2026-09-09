package tz.co.nlolo.lifeplatform.communication.infrastructure;

import tz.co.nlolo.lifeplatform.communication.domain.NotificationDispatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface NotificationDispatchRepository extends JpaRepository<NotificationDispatch, UUID> {

    /**
     * The reminder queue, oldest first.
     *
     * <p>Deliberately not tenant-scoped: this is read by a scheduled drain with no request and no
     * ambient tenant, over rows a cross-tenant sweep produced. Every row carries its own
     * tenant_id and the dispatcher sets the context from it before doing anything.
     */
    List<NotificationDispatch> findByStatusOrderByCreatedAtAsc(String status);

    /**
     * Take a queued reminder, if it is still there to take.
     *
     * <p>Conditional on PENDING, so of two instances draining the same queue exactly one gets a
     * row back. A read-then-write in Java would let both pass the check and both send.
     *
     * @return 1 if this caller claimed it, 0 if somebody else already had.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update NotificationDispatch d set d.status = 'CLAIMED' "
        + "where d.dispatchId = :dispatchId and d.status = 'PENDING'")
    int claimForSending(@Param("dispatchId") UUID dispatchId);

    List<NotificationDispatch> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    List<NotificationDispatch> findByTenantIdAndPartyIdOrderByCreatedAtDesc(UUID tenantId, UUID partyId);

    /** What the policy detail page's Messages panel reads. */
    List<NotificationDispatch> findByTenantIdAndPolicyNumberOrderByCreatedAtDesc(UUID tenantId, String policyNumber);

    /**
     * Whether this policy has already had this message. The reminder sweep's guard against
     * telling somebody twice that their offer is closing.
     */
    boolean existsByTenantIdAndPolicyNumberAndTemplateKey(UUID tenantId, String policyNumber, String templateKey);
}
