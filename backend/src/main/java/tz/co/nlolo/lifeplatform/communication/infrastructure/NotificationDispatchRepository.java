package tz.co.nlolo.lifeplatform.communication.infrastructure;

import tz.co.nlolo.lifeplatform.communication.domain.NotificationDispatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotificationDispatchRepository extends JpaRepository<NotificationDispatch, UUID> {

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
