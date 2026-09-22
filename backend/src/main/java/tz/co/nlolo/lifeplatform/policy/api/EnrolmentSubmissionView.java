package tz.co.nlolo.lifeplatform.policy.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A lender's submitted schedule and where it stands.
 *
 * @param rowCount every non-blank line of the file. {@code enrolledCount + rejectedCount}
 *     will not always equal it: enrolled is zero until the submission is accepted, which
 *     is the distinction this whole feature exists to make.
 * @param enrolledCount borrowers actually put on risk. Zero while PENDING.
 * @param rejectedCount rows that carry no cover and never will under this submission.
 */
public record EnrolmentSubmissionView(UUID submissionId,
                                       String policyNumber,
                                       SubmissionStatus status,
                                       String fileName,
                                       int rowCount,
                                       int enrolledCount,
                                       int rejectedCount,
                                       String submittedBy,
                                       Instant submittedAt,
                                       String acceptedBy,
                                       Instant acceptedAt) {}
