package tz.co.nlolo.lifeplatform.unitlinked.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.unitlinked.domain.UnitStatement;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UnitStatementRepository extends JpaRepository<UnitStatement, UUID> {

    List<UnitStatement> findByTenantIdAndPolicyNumberOrderByPeriodToDescGeneratedAtDesc(UUID tenantId, String policyNumber);

    boolean existsByTenantIdAndPolicyNumberAndKindAndPeriodTo(UUID tenantId, String policyNumber, String kind,
                                                             LocalDate periodTo);

    Optional<UnitStatement> findByTenantIdAndStatementId(UUID tenantId, UUID statementId);
}
