package tz.co.nlolo.lifeplatform.unitlinked.infrastructure;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.nlolo.lifeplatform.unitlinked.domain.PendingOrder;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Every finder takes the tenant: tests connect as the table owner, so RLS alone would not scope them. */
public interface PendingOrderRepository extends JpaRepository<PendingOrder, UUID> {

    /**
     * The fund's orders waiting on any date up to {@code upTo}, oldest first, locked: one approved price prices
     * them, and a concurrent second approval of the same fund waits behind it rather than pricing them twice.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PendingOrder o where o.tenantId = :tenantId and o.fundId = :fundId and o.status = 'WAITING'"
        + " and o.boundDate <= :upTo order by o.receivedAt, o.orderId")
    List<PendingOrder> findWaiting(@Param("tenantId") UUID tenantId, @Param("fundId") UUID fundId,
                                   @Param("upTo") LocalDate upTo);

    List<PendingOrder> findByTenantIdAndPolicyNumberAndStatusOrderByReceivedAt(UUID tenantId, String policyNumber,
                                                                               String status);

    List<PendingOrder> findByTenantIdAndSourceTypeAndSourceRef(UUID tenantId, String sourceType, String sourceRef);

    boolean existsByTenantIdAndSourceTypeAndSourceRef(UUID tenantId, String sourceType, String sourceRef);

    @Query("select count(o) from PendingOrder o where o.tenantId = :tenantId and o.sourceType = :sourceType"
        + " and o.sourceRef = :sourceRef and o.status = 'WAITING'")
    long countWaiting(@Param("tenantId") UUID tenantId, @Param("sourceType") String sourceType,
                      @Param("sourceRef") String sourceRef);

    /** One charge date's orders: every source ref under {@code prefix} (charge:<policy>:<date>:). */
    @Query("select o from PendingOrder o where o.tenantId = :tenantId and o.sourceType = :sourceType"
        + " and o.sourceRef like concat(:prefix, '%')")
    List<PendingOrder> findBySourcePrefix(@Param("tenantId") UUID tenantId, @Param("sourceType") String sourceType,
                                          @Param("prefix") String prefix);

    /** Waiting orders per bound date for a fund: the console's "waiting for a price" counts. */
    @Query("select o.boundDate, count(o) from PendingOrder o where o.tenantId = :tenantId and o.fundId = :fundId"
        + " and o.status = 'WAITING' group by o.boundDate order by o.boundDate")
    List<Object[]> waitingByDate(@Param("tenantId") UUID tenantId, @Param("fundId") UUID fundId);
}
