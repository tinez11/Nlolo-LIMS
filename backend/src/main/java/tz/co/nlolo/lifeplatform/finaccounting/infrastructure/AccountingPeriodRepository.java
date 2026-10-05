package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.nlolo.lifeplatform.finaccounting.domain.AccountingPeriod;
import tz.co.nlolo.lifeplatform.finaccounting.domain.AccountingPeriodId;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every finder takes the tenant: tests connect as the table owner, so RLS alone would not scope them. */
public interface AccountingPeriodRepository extends JpaRepository<AccountingPeriod, AccountingPeriodId> {

    Optional<AccountingPeriod> findByTenantIdAndPeriod(UUID tenantId, String period);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from AccountingPeriod p where p.tenantId = :tenantId and p.period = :period")
    Optional<AccountingPeriod> lockFor(@Param("tenantId") UUID tenantId, @Param("period") String period);

    List<AccountingPeriod> findByTenantIdOrderByPeriodDesc(UUID tenantId);

    /** The latest period before {@code period} that is not locked yet -- one that must be locked first. */
    @Query("select p from AccountingPeriod p where p.tenantId = :tenantId and p.period < :period"
        + " and p.status <> tz.co.nlolo.lifeplatform.finaccounting.api.PeriodStatus.LOCKED order by p.period desc")
    List<AccountingPeriod> unlockedBefore(@Param("tenantId") UUID tenantId, @Param("period") String period);
}
