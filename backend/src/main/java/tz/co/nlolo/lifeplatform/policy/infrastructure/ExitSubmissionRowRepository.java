package tz.co.nlolo.lifeplatform.policy.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.policy.domain.ExitSubmissionRow;

import java.util.List;
import java.util.UUID;

public interface ExitSubmissionRowRepository extends JpaRepository<ExitSubmissionRow, UUID> {

    /** In the LENDER's own line order, so a report reads beside the file that produced it. */
    List<ExitSubmissionRow> findByTenantIdAndSubmissionIdOrderByLineNumberAsc(
        UUID tenantId, UUID submissionId);
}
