package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.nlolo.lifeplatform.finaccounting.domain.PolicyElection;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every finder takes the tenant: tests connect as the table owner, so RLS alone would not scope them. */
public interface PolicyElectionRepository extends JpaRepository<PolicyElection, UUID> {

    /** The register's current version for a tenant: the highest approved one, 0 before any. */
    @Query("select coalesce(max(e.registerVersion), 0) from PolicyElection e where e.tenantId = :tenantId")
    int currentVersion(@Param("tenantId") UUID tenantId);

    boolean existsByTenantId(UUID tenantId);

    Optional<PolicyElection> findByTenantIdAndElectionId(UUID tenantId, UUID electionId);

    /** The approved elections for one key and scope that apply on or before {@code on}, latest first. */
    @Query("select e from PolicyElection e where e.tenantId = :tenantId and e.key = :key and e.scope = :scope"
        + " and e.status = 'APPROVED' and e.effectiveFrom <= :on order by e.effectiveFrom desc, e.registerVersion desc")
    List<PolicyElection> approvedOnOrBefore(@Param("tenantId") UUID tenantId, @Param("key") String key,
                                            @Param("scope") String scope, @Param("on") LocalDate on);

    List<PolicyElection> findByTenantIdOrderByKeyAscScopeAscEffectiveFromDesc(UUID tenantId);
}
