package tz.co.nlolo.lifeplatform.policy.api;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/**
 * Bulk enrolment of a lender's borrowers onto a credit-life scheme.
 *
 * <p><b>Submitting enrols nobody.</b> A submission is read, judged row by row and
 * recorded; a SECOND staff user accepts it and only then does anybody go on risk. Spec
 * §2.10 -- a counterparty with a direct financial interest in maximising cover must not
 * create the insurer's liability unattended, and because cover backdates to the
 * disbursement date the wait costs throughput rather than risk.
 */
public interface EnrolmentApi {

    /**
     * Read and judge a lender's schedule. Nobody is enrolled.
     *
     * @throws tz.co.nlolo.lifeplatform.policy.api.InvalidPolicyStateException if the
     *     scheme is not credit life, if a submission is already awaiting acceptance on
     *     it, or if the FILE is unreadable -- a missing required column fails the whole
     *     file, because every row would fail identically.
     */
    EnrolmentSubmissionView submit(String policyNumber, InputStream file, String fileName,
                                    String submittedBy);

    /**
     * A second person turns the acceptable rows into cover.
     *
     * @throws tz.co.nlolo.lifeplatform.policy.api.InvalidPolicyStateException if the
     *     submission is not pending, or if {@code acceptedBy} is the person who
     *     submitted it.
     */
    EnrolmentSubmissionView accept(UUID submissionId, String acceptedBy);

    /**
     * Abandon a pending submission without enrolling anybody.
     *
     * <p>The submitter may do this themselves: the two-person rule exists to stop cover
     * being created unattended, not to stop it being declined.
     */
    EnrolmentSubmissionView withdraw(UUID submissionId, String withdrawnBy);

    EnrolmentSubmissionView getSubmission(UUID submissionId);

    /** Every line of the file and its outcome, in the lender's own line order. */
    List<EnrolmentRowView> listRows(UUID submissionId);

    /**
     * Every submission this scheme has had, newest first.
     *
     * <p>Exists because every other operation on this interface takes a {@code submissionId} the
     * caller is assumed to already hold. That is true of the upload flow, which has just created
     * one, and false of anybody arriving at a scheme cold — so without this a console page that
     * opens on a scheme has nothing to render and no way to reach the reports of files already
     * sent.
     *
     * <p>Newest first because a lender's latest file is what staff came to look at.
     *
     * <p>Empty, never an exception, for a scheme that has had none. That is the normal state of a
     * scheme on its first day rather than an error, and throwing would make a page render a
     * failure for a situation that is not one.
     */
    List<EnrolmentSubmissionView> listSubmissions(String policyNumber);

    /** The report that goes back to the lender, as CSV. */
    String renderReport(UUID submissionId);
}
