package tz.co.nlolo.lifeplatform.regreporting.infrastructure;

import tz.co.nlolo.lifeplatform.regreporting.domain.ReturnLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ReturnLineRepository extends JpaRepository<ReturnLine, UUID> {
    List<ReturnLine> findByTenantIdAndReturnIdOrderByLineNoAsc(UUID tenantId, UUID returnId);

    /** Batch-loads every return's lines in one query -- used by {@code listReturns} so it does not
     * N+1 across the returns it lists, the exact defect M9's final review found in
     * {@code finaccounting}'s equivalent list endpoint. */
    List<ReturnLine> findByTenantIdAndReturnIdInOrderByReturnIdAscLineNoAsc(UUID tenantId, Collection<UUID> returnIds);

    void deleteByTenantIdAndReturnId(UUID tenantId, UUID returnId);
}
