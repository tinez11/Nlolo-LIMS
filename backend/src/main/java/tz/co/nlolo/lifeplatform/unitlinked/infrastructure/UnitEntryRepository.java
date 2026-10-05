package tz.co.nlolo.lifeplatform.unitlinked.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.nlolo.lifeplatform.unitlinked.domain.UnitEntry;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Every finder takes the tenant: tests connect as the table owner, so RLS alone would not scope them. */
public interface UnitEntryRepository extends JpaRepository<UnitEntry, UUID> {

    @Query("select coalesce(sum(e.units), 0) from UnitEntry e where e.tenantId = :tenantId"
        + " and e.policyNumber = :policyNumber and e.fundId = :fundId")
    BigDecimal sumUnits(@Param("tenantId") UUID tenantId, @Param("policyNumber") String policyNumber,
                        @Param("fundId") UUID fundId);

    /** Units per fund for one policy: [fundId, units]. Funds with a zero holding are included. */
    @Query("select e.fundId, sum(e.units) from UnitEntry e where e.tenantId = :tenantId"
        + " and e.policyNumber = :policyNumber and e.fundId is not null group by e.fundId")
    List<Object[]> holdings(@Param("tenantId") UUID tenantId, @Param("policyNumber") String policyNumber);

    /** Every unit of the fund held by every policy of the tenant: what a new price revalues. */
    @Query("select coalesce(sum(e.units), 0) from UnitEntry e where e.tenantId = :tenantId and e.fundId = :fundId")
    BigDecimal unitsInIssue(@Param("tenantId") UUID tenantId, @Param("fundId") UUID fundId);

    @Query("select e from UnitEntry e where e.tenantId = :tenantId and e.sourceType = :sourceType"
        + " and e.sourceRef like concat(:prefix, '%')")
    List<UnitEntry> findBySourcePrefix(@Param("tenantId") UUID tenantId, @Param("sourceType") String sourceType,
                                       @Param("prefix") String prefix);

    boolean existsByTenantIdAndPolicyNumberAndEntryType(UUID tenantId, String policyNumber, String entryType);

    List<UnitEntry> findByTenantIdAndPolicyNumberOrderByCreatedAt(UUID tenantId, String policyNumber);

    List<UnitEntry> findByTenantIdAndPriceId(UUID tenantId, UUID priceId);

    List<UnitEntry> findByTenantIdAndOrderId(UUID tenantId, UUID orderId);

    List<UnitEntry> findByTenantIdAndSourceTypeAndSourceRef(UUID tenantId, String sourceType, String sourceRef);

    boolean existsByTenantIdAndSourceTypeAndSourceRefAndEntryType(UUID tenantId, String sourceType, String sourceRef,
                                                                 String entryType);
}
