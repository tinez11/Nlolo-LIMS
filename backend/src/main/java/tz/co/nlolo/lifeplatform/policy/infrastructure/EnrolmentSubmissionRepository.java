package tz.co.nlolo.lifeplatform.policy.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.policy.api.SubmissionStatus;
import tz.co.nlolo.lifeplatform.policy.domain.EnrolmentSubmission;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EnrolmentSubmissionRepository extends JpaRepository<EnrolmentSubmission, UUID> {

    Optional<EnrolmentSubmission> findBySubmissionIdAndTenantId(UUID submissionId, UUID tenantId);

    /**
     * Backs the readable "one file in flight" error in front of
     * {@code ux_enrolment_submission_in_flight}, which remains the guarantee.
     */
    Optional<EnrolmentSubmission> findByTenantIdAndPolicyNumberAndStatus(
        UUID tenantId, String policyNumber, SubmissionStatus status);

    List<EnrolmentSubmission> findByTenantIdAndPolicyNumberOrderBySubmittedAtDesc(
        UUID tenantId, String policyNumber);
}
