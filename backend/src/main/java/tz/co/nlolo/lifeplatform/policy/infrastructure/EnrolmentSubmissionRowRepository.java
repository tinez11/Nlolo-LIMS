package tz.co.nlolo.lifeplatform.policy.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.policy.domain.EnrolmentSubmissionRow;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EnrolmentSubmissionRowRepository extends JpaRepository<EnrolmentSubmissionRow, UUID> {

    /**
     * Ordered by the line in the lender's own file, so a report can be read beside the
     * spreadsheet that produced it.
     */
    List<EnrolmentSubmissionRow> findByTenantIdAndSubmissionIdOrderByLineNumberAsc(
        UUID tenantId, UUID submissionId);

    /**
     * The row that enrolled this member, and therefore what they were charged and which file
     * charged it.
     *
     * <p>Optional because not every member came from a file: an opening-schedule member joined
     * at issuance, before any enrolment file existed, and no row records a premium for them.
     * Treating that as impossible is how a refund path throws inside an AFTER_COMMIT listener.
     */
    Optional<EnrolmentSubmissionRow> findByTenantIdAndPolicyMemberId(
        UUID tenantId, UUID policyMemberId);
}
