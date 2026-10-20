package tz.co.nlolo.lifeplatform.underwriting.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.underwriting.domain.CaseEvent;

import java.util.List;
import java.util.UUID;

public interface CaseEventRepository extends JpaRepository<CaseEvent, UUID> {

    /** Oldest first, tie-broken on the id: two events in one request may share a timestamp. */
    List<CaseEvent> findByTenantIdAndCaseIdOrderByCreatedAtAscEventIdAsc(UUID tenantId, UUID caseId);
}
