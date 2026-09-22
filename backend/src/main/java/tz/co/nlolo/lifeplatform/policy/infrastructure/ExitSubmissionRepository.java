package tz.co.nlolo.lifeplatform.policy.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.policy.api.SubmissionStatus;
import tz.co.nlolo.lifeplatform.policy.domain.ExitSubmission;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExitSubmissionRepository extends JpaRepository<ExitSubmission, UUID> {

    Optional<ExitSubmission> findBySubmissionIdAndTenantId(UUID submissionId, UUID tenantId);

    /** Backs the readable error in front of ux_exit_submission_in_flight. */
    Optional<ExitSubmission> findByTenantIdAndPolicyNumberAndStatus(
        UUID tenantId, String policyNumber, SubmissionStatus status);

    List<ExitSubmission> findByTenantIdAndPolicyNumberOrderBySubmittedAtDesc(
        UUID tenantId, String policyNumber);
}
