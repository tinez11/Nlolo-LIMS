package tz.co.nlolo.lifeplatform.party.infrastructure;

import tz.co.nlolo.lifeplatform.party.api.KycStatus;
import tz.co.nlolo.lifeplatform.party.domain.Party;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface PartyRepository extends JpaRepository<Party, UUID> {
    Optional<Party> findByTenantIdAndRegistrationNumber(UUID tenantId, String registrationNumber);

    /** Four combinations of two optional filters -- same shape as PolicyApiImpl.searchPolicies's
     *  own branching, kept as plain derived methods (not a null-safe JPQL query) since two
     *  dimensions is still small enough to enumerate directly. */
    Page<Party> findByTenantId(UUID tenantId, Pageable pageable);
    Page<Party> findByTenantIdAndKycStatus(UUID tenantId, KycStatus kycStatus, Pageable pageable);
    Page<Party> findByTenantIdAndCreatedBy(UUID tenantId, String createdBy, Pageable pageable);
    Page<Party> findByTenantIdAndKycStatusAndCreatedBy(UUID tenantId, KycStatus kycStatus, String createdBy, Pageable pageable);

    /**
     * The three-dimension combined filter -- kycStatus x createdBy x q -- following the
     * same null-safe JPQL pattern PolicyRepository.search/ClaimRepository.search already
     * established for their own 3-way filters, rather than enumerating 8 derived-method
     * combinations. `q` is a case-insensitive substring match against displayName; a null
     * `q` means "no text filter", not "match nothing".
     */
    @Query("SELECT p FROM Party p WHERE p.tenantId = :tenantId "
        + "AND (:kycStatus IS NULL OR p.kycStatus = :kycStatus) "
        + "AND (:createdBy IS NULL OR p.createdBy = :createdBy) "
        + "AND (:q IS NULL OR LOWER(p.displayName) LIKE LOWER(CONCAT('%', :q, '%')))")
    Page<Party> search(@Param("tenantId") UUID tenantId, @Param("kycStatus") KycStatus kycStatus,
                        @Param("createdBy") String createdBy, @Param("q") String q, Pageable pageable);

    /**
     * Ids only, for another module to scope its own query by. Projected rather than returning
     * whole {@code Party} rows: the caller needs a set to filter on, and loading every column
     * (including the PII this module exists to guard) to read one id back would be the wrong
     * trade.
     */
    @Query("SELECT p.partyId FROM Party p WHERE p.tenantId = :tenantId AND p.createdBy = :createdBy")
    Set<UUID> findPartyIdsByTenantIdAndCreatedBy(@Param("tenantId") UUID tenantId,
                                                   @Param("createdBy") String createdBy);

    boolean existsByPartyIdAndTenantIdAndCreatedBy(UUID partyId, UUID tenantId, String createdBy);
}
