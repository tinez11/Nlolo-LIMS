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
}
