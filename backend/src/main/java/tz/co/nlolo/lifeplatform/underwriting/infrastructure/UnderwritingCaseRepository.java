package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import tz.co.nlolo.lifeplatform.underwriting.domain.UnderwritingCase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UnderwritingCaseRepository extends JpaRepository<UnderwritingCase, UUID> {
    Optional<UnderwritingCase> findByCaseIdAndTenantId(UUID caseId, UUID tenantId);
    Page<UnderwritingCase> findByTenantId(UUID tenantId, Pageable pageable);
    Page<UnderwritingCase> findByTenantIdAndStatus(UUID tenantId, String status, Pageable pageable);
}
