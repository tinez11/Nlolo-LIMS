package tz.co.nlolo.lifeplatform.policy.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.policy.domain.EnrolmentSubmissionRow;

import java.util.List;
import java.util.UUID;

public interface EnrolmentSubmissionRowRepository extends JpaRepository<EnrolmentSubmissionRow, UUID> {

    /**
     * Ordered by the line in the lender's own file, so a report can be read beside the
     * spreadsheet that produced it.
     */
    List<EnrolmentSubmissionRow> findByTenantIdAndSubmissionIdOrderByLineNumberAsc(
        UUID tenantId, UUID submissionId);
}
