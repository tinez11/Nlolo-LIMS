package tz.co.nlolo.lifeplatform.policy.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.policy.domain.GroupScheme;

import java.util.Optional;
import java.util.UUID;

public interface GroupSchemeRepository extends JpaRepository<GroupScheme, String> {
    /**
     * Tenant-scoped read. RLS enforces isolation per connection, but the explicit
     * predicate matches the fail-loud idiom the rest of this module uses -- a
     * cross-tenant policy number reads as absent rather than as somebody else's scheme.
     */
    Optional<GroupScheme> findByPolicyNumberAndTenantId(String policyNumber, UUID tenantId);

    /** The scheme row, locked for this transaction: adding a yearly agreement checks for an overlap under it. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select g from GroupScheme g where g.policyNumber = :policyNumber and g.tenantId = :tenantId")
    Optional<GroupScheme> lockScheme(@org.springframework.data.repository.query.Param("policyNumber") String policyNumber,
                                     @org.springframework.data.repository.query.Param("tenantId") UUID tenantId);
}
