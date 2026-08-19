package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import tz.co.nlolo.lifeplatform.finaccounting.domain.GlPosting;
import tz.co.nlolo.lifeplatform.finaccounting.domain.GlPostingId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GlPostingRepository extends JpaRepository<GlPosting, GlPostingId> {
    List<GlPosting> findByTenantIdAndJournalEntryIdOrderByDirectionAsc(UUID tenantId, UUID journalEntryId);
    List<GlPosting> findByTenantIdAndAccountCodeAndPeriod(UUID tenantId, String accountCode, String period);
}
