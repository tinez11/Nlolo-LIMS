package tz.co.nlolo.lifeplatform.policy.api;

import java.math.BigDecimal;
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
                                       Instant acceptedAt,
                                       /**
                                        * What the insurer charged for this file, and the figure
                                        * its invoice is built from. Null until acceptance.
                                        */
                                       BigDecimal premiumTotal,
                                       /**
                                        * What the LENDER's own file said, or null where it
                                        * carried no premium column — which is legitimate, and
                                        * different from stating zero.
                                        */
                                       BigDecimal statedPremiumTotal,
                                       /**
                                        * The first less the second, or null where there is
                                        * nothing to compare. Positive means the lender is
                                        * invoiced more than their file predicted.
                                        *
                                        * <p>Returned rather than left to the caller so two
                                        * clients cannot subtract it in two directions and
                                        * disagree about the sign of a reconciliation.
                                        */
                                       BigDecimal premiumVariance) {}
