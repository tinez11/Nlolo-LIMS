package tz.co.nlolo.lifeplatform.party.infrastructure;

import tz.co.nlolo.lifeplatform.party.api.KycStatus;
import tz.co.nlolo.lifeplatform.party.api.PartyType;
import tz.co.nlolo.lifeplatform.party.domain.Party;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import tz.co.nlolo.lifeplatform.party.api.IdType;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

public interface PartyRepository extends JpaRepository<Party, UUID> {
    Optional<Party> findByTenantIdAndRegistrationNumber(UUID tenantId, String registrationNumber);

    /**
     * The fast-path duplicate check behind {@code ux_party_individual_identity}. The index
     * is the guarantee; this only lets the caller raise a domain exception instead of
     * surfacing a raw constraint violation.
     */
    Optional<Party> findByTenantIdAndIdTypeAndIdNumber(UUID tenantId, IdType idType, String idNumber);

    /** Four combinations of two optional filters -- same shape as PolicyApiImpl.searchPolicies's
     *  own branching, kept as plain derived methods (not a null-safe JPQL query) since two
     *  dimensions is still small enough to enumerate directly. */
    Page<Party> findByTenantId(UUID tenantId, Pageable pageable);
    Page<Party> findByTenantIdAndKycStatus(UUID tenantId, KycStatus kycStatus, Pageable pageable);
    Page<Party> findByTenantIdAndCreatedBy(UUID tenantId, String createdBy, Pageable pageable);
    Page<Party> findByTenantIdAndKycStatusAndCreatedBy(UUID tenantId, KycStatus kycStatus, String createdBy, Pageable pageable);

    /**
     * The four-dimension combined filter -- kycStatus x createdBy x q x partyType -- following
     * the same null-safe JPQL pattern PolicyRepository.search/ClaimRepository.search already
     * established for their own multi-way filters, rather than enumerating 16 derived-method
     * combinations. `q` is a case-insensitive substring match against displayName; a null
     * `q` means "no text filter", not "match nothing".
     *
     * <p>{@code partyType} is the dimension the client register is split on: individuals are
     * one working area and corporates/groups another, and the two have different reviewers and
     * different KYC evidence. It has to be filtered HERE rather than in the client, because a
     * client-side filter over one page of results would report "the individuals among the
     * first 20 of 775" as though it were the individual register, and its count would be
     * wrong. It takes a COLLECTION rather than a single type because the register's second
     * area is "corporate and groups" -- two of the three enum values in one working list --
     * and a single-value param would have made that area two requests whose pages and totals
     * could not be combined into one honest pager.
     */
    /*
     * `CAST(:q AS string)` is not decoration, and removing it breaks this query for every
     * caller that does not pass `q`.
     *
     * The `:q IS NULL OR ...` guard reads as null-safe and was not: with a null bind,
     * Postgres has nothing to infer the parameter's type from inside
     * `lower('%' || ? || '%')`, so it defaults to bytea and the statement dies on
     * `function lower(bytea) does not exist`. The whole query 500s -- the guard never gets
     * the chance to short-circuit, because this is a SQL preparation failure, not a
     * predicate result.
     *
     * It stayed hidden because the only caller routed to this query exclusively when `q`
     * was present, so the null branch of a clause written to handle null was never once
     * executed. Adding the partyType dimension is what first sent a null `q` through here.
     */
    @Query("SELECT p FROM Party p WHERE p.tenantId = :tenantId "
        + "AND (:kycStatus IS NULL OR p.kycStatus = :kycStatus) "
        + "AND (:createdBy IS NULL OR p.createdBy = :createdBy) "
        + "AND (:q IS NULL OR LOWER(p.displayName) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))) "
        + "AND (:partyTypes IS NULL OR p.partyType IN :partyTypes)")
    Page<Party> search(@Param("tenantId") UUID tenantId, @Param("kycStatus") KycStatus kycStatus,
                        @Param("createdBy") String createdBy, @Param("q") String q,
                        @Param("partyTypes") Collection<PartyType> partyTypes, Pageable pageable);

    /**
     * Ids only, for another module to scope its own query by. Projected rather than returning
     * whole {@code Party} rows: the caller needs a set to filter on, and loading every column
     * (including the PII this module exists to guard) to read one id back would be the wrong
     * trade.
     */
    @Query("SELECT p.partyId FROM Party p WHERE p.tenantId = :tenantId AND p.createdBy = :createdBy")
    Set<UUID> findPartyIdsByTenantIdAndCreatedBy(@Param("tenantId") UUID tenantId,
                                                   @Param("createdBy") String createdBy);

    /**
     * The same ids-only projection, matched on name instead of creator, so another module
     * can scope its own query by "the parties called something like this".
     *
     * <p>It exists because a group scheme's member rows hold a {@code memberPartyId} and no
     * name -- the name lives here, in the module that guards it. Searching a 500-life roll
     * for one person therefore has to resolve names to ids first; the alternative would be a
     * join across the two modules' tables, which the module boundary forbids.
     *
     * <p>No null guard and no {@code CAST}: unlike {@code search}, this is only ever called
     * with a non-blank {@code q}, so Postgres always has a text bind to infer from.
     */
    @Query("SELECT p.partyId FROM Party p WHERE p.tenantId = :tenantId "
        + "AND LOWER(p.displayName) LIKE LOWER(CONCAT('%', :q, '%'))")
    Set<UUID> findPartyIdsByTenantIdAndDisplayNameLike(@Param("tenantId") UUID tenantId,
                                                         @Param("q") String q);

    boolean existsByPartyIdAndTenantIdAndCreatedBy(UUID partyId, UUID tenantId, String createdBy);
}
