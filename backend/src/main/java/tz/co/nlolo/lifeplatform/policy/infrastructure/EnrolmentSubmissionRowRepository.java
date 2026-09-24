package tz.co.nlolo.lifeplatform.policy.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.nlolo.lifeplatform.policy.domain.EnrolmentSubmissionRow;

import java.util.Collection;
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
    /**
     * Which file each of these members arrived on.
     *
     * <p>ONE query for a page of members rather than one per row. A roll of several hundred
     * borrowers would otherwise issue several hundred lookups to answer a question that is a
     * single join, and the member list is the screen most likely to be paged through quickly.
     *
     * <p>A member with no row here did not come from a file at all -- they were on the opening
     * schedule, or added one at a time on an employer scheme. That absence is meaningful and the
     * caller renders it as such rather than as a blank.
     */
    @Query(value = """
        select r.policy_member_id as policyMemberId,
               s.submission_id    as submissionId,
               s.file_name        as fileName
          from policy.enrolment_submission_row r
          join policy.enrolment_submission s on s.submission_id = r.submission_id
         where r.tenant_id = :tenantId
           and r.policy_member_id in (:memberIds)
        """, nativeQuery = true)
    List<MemberArrival> findArrivalsForMembers(@Param("tenantId") UUID tenantId,
                                                @Param("memberIds") Collection<UUID> memberIds);

    /** Where one member came from: the file, and the submission it can be opened at. */
    interface MemberArrival {
        UUID getPolicyMemberId();
        UUID getSubmissionId();
        String getFileName();
    }

    Optional<EnrolmentSubmissionRow> findByTenantIdAndPolicyMemberId(
        UUID tenantId, UUID policyMemberId);
}
