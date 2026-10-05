package tz.co.nlolo.lifeplatform.unitlinked.infrastructure;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.nlolo.lifeplatform.unitlinked.domain.FundLiability;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FundLiabilityRepository extends JpaRepository<FundLiability, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from FundLiability l where l.tenantId = :tenantId and l.fundId = :fundId")
    Optional<FundLiability> lockFor(@Param("tenantId") UUID tenantId, @Param("fundId") UUID fundId);

    List<FundLiability> findByTenantId(UUID tenantId);
}
