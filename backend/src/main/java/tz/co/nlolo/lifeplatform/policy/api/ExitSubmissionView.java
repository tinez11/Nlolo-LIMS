package tz.co.nlolo.lifeplatform.policy.api;

import java.time.Instant;
import java.util.UUID;

/** An exits file and where it has got to. Mirrors EnrolmentSubmissionView exactly. */
public record ExitSubmissionView(UUID submissionId,
                                  String policyNumber,
                                  SubmissionStatus status,
                                  String fileName,
                                  int rowCount,
                                  int exitedCount,
                                  int rejectedCount,
                                  String submittedBy,
                                  Instant submittedAt,
                                  String acceptedBy,
                                  Instant acceptedAt) {}
