package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import tz.co.nlolo.lifeplatform.underwriting.domain.UnderwritingCase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface UnderwritingCaseRepository extends JpaRepository<UnderwritingCase, UUID> {
    Optional<UnderwritingCase> findByCaseIdAndTenantId(UUID caseId, UUID tenantId);
    Page<UnderwritingCase> findByTenantId(UUID tenantId, Pageable pageable);
    Page<UnderwritingCase> findByTenantIdAndStatus(UUID tenantId, String status, Pageable pageable);

    /**
     * Three optional dimensions -- status x applicantPartyId x agent scope -- in one null-safe
     * query, following {@code ClaimRepository.search}'s exact shape rather than enumerating derived
     * methods for every combination.
     *
     * <p>{@code applicantPartyIds} is the agent scope. Underwriting cases carry no agent reference
     * of their own, so the caller resolves the set via {@code PartyApi.partyIdsRegisteredBy} and
     * passes it in -- the same "join through the module that owns the fact" idiom claims uses with
     * {@code PolicyApi.policyNumbersForAgentTeam}, rather than underwriting taking a dependency on
     * distribution.
     *
     * <p>NULL means "no agent filter" (staff). An EMPTY set means "filter to nothing" and must
     * return zero rows -- an agent who has registered no one sees no cases, which is not the same
     * as seeing everything. Passing null for a caller that should have been scoped is the failure
     * mode this distinction exists to prevent.
     */
    @Query("SELECT c FROM UnderwritingCase c WHERE c.tenantId = :tenantId "
        + "AND (:status IS NULL OR c.status = :status) "
        + "AND (:applicantPartyId IS NULL OR c.applicantPartyId = :applicantPartyId) "
        + "AND (:applicantPartyIds IS NULL OR c.applicantPartyId IN :applicantPartyIds)")
    Page<UnderwritingCase> search(@Param("tenantId") UUID tenantId,
                                   @Param("status") String status,
                                   @Param("applicantPartyId") UUID applicantPartyId,
                                   @Param("applicantPartyIds") Set<UUID> applicantPartyIds,
                                   Pageable pageable);
}
