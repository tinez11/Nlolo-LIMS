package tz.co.nlolo.lifeplatform.distribution.infrastructure;

import tz.co.nlolo.lifeplatform.distribution.api.LicenseStatus;
import tz.co.nlolo.lifeplatform.distribution.domain.AgentProfile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every method is tenant-scoped -- RLS is the backstop, not the only guard, per this project's
 * established convention (see any repository in payment or claims). */
public interface AgentProfileRepository extends JpaRepository<AgentProfile, UUID> {
    Optional<AgentProfile> findByAgentIdAndTenantId(UUID agentId, UUID tenantId);

    /**
     * ORDERED, and the order is load-bearing rather than cosmetic.
     *
     * <p>Nothing in the DDL stops one party holding several {@code AgentProfile} rows, and in
     * practice they do: this platform's own dev database has a party carrying 46 of them. Every
     * caller here picks ONE of the rows — {@code agentIdForParty} takes the first ACTIVE one to
     * decide WHO GETS PAID — so an unordered query meant that answer was whatever Postgres
     * happened to return first, and could differ between two identical calls.
     *
     * <p>Newest first, matching the ordering {@code resolveApplicablePlan} already settled on for
     * exactly the same class of defect one lookup over ("findFirst() off a query that had no
     * ORDER BY used to mean 'whichever ACTIVE plan Postgres returned first'"). One convention for
     * both, rather than two: a newer profile is the agent's current licence, an older one is
     * superseded.
     */
    List<AgentProfile> findByTenantIdAndPartyIdOrderByCreatedAtDescAgentIdDesc(UUID tenantId, UUID partyId);

    List<AgentProfile> findByTenantIdAndHierarchyParentId(UUID tenantId, UUID hierarchyParentId);

    /**
     * M13: the agents list. Until now `distribution` served every per-agent read but
     * no list, so an agent table had no feed at all and the nav item pointed at the
     * onboarding form instead.
     */
    Page<AgentProfile> findByTenantId(UUID tenantId, Pageable pageable);

    Page<AgentProfile> findByTenantIdAndLicenseStatus(UUID tenantId, LicenseStatus licenseStatus, Pageable pageable);

    /**
     * Matches a licence number OR a person's NAME.
     *
     * <p>An agent still has no name in this module — the name lives in {@code party} — so the
     * name half is not a join. The caller resolves {@code q} to party ids through
     * {@code PartyApi.partyIdsMatchingName} and passes them in, the same ids-only idiom the
     * group-scheme member roll already uses to search a schedule by member name. Distribution
     * filters ids it already holds; it never reads another context's data.
     *
     * <p>Matching in ONE query, rather than searching licence numbers and names separately and
     * merging, is what keeps the page and its {@code totalElements} honest: a merged result could
     * not report a total it had not actually counted.
     *
     * <p><b>{@code partyIds} must never be empty.</b> An empty {@code in} list is not valid SQL,
     * and a null one would silently widen this to "every agent" — the exact collapse
     * {@code partyIdsMatchingName}'s own javadoc warns callers about. {@link #NO_PARTY_MATCH} is
     * the sentinel to pass when a name search matched nobody.
     */
    @Query("""
        select a from AgentProfile a
        where a.tenantId = :tenantId
          and (upper(a.licenseNumber) like upper(concat('%', :q, '%'))
               or a.partyId in :partyIds)
          and (:status is null or a.licenseStatus = :status)
        """)
    Page<AgentProfile> search(@Param("tenantId") UUID tenantId, @Param("q") String q,
                              @Param("partyIds") Collection<UUID> partyIds,
                              @Param("status") LicenseStatus status, Pageable pageable);

    /** Stands in for "no party name matched", so {@link #search}'s {@code in} clause is never
     *  handed an empty collection. The nil UUID, which {@code gen_random_uuid()} cannot produce,
     *  so it matches no real {@code party_id}. */
    Collection<UUID> NO_PARTY_MATCH = List.of(new UUID(0L, 0L));

    /** Task 8's license-expiry sweep: agents whose license is still recorded ACTIVE but whose
     * expiry date has passed. Tenant-scoped like every other query here, per this project's
     * hard tenantId convention -- Task 8 owns how it is invoked across tenants. */
    List<AgentProfile> findByTenantIdAndLicenseStatusAndLicenseExpiryDateLessThanEqual(
        UUID tenantId, LicenseStatus licenseStatus, LocalDate date);
}
