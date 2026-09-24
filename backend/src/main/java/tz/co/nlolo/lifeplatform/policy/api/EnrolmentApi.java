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

    /**
     * The blank schedule this scheme's lender fills in, with one of their OWN borrowers already
     * in it as a worked example.
     *
     * <p><b>Scoped to a scheme rather than to the product, and the example is the point.</b> The
     * columns are the same for every lender, so a product-wide blank looked like the right shape
     * — but a header row plus a page of prose is not how anybody learns a file format. The
     * borrower a person typed into the set-up form, echoed back in the file they are about to
     * send, answers the questions the prose was trying to: what a date looks like, where the
     * amount goes, what a member reference the insurer minted actually reads like.
     *
     * <p><b>The example row is safe to return unchanged.</b> It names a loan already on cover, and
     * the judge refuses a row whose borrower, date of birth, disbursement date and principal match
     * an existing member — unconditionally, without consulting the reference. So a lender who
     * sends the template back as-is gets ALREADY_ENROLLED on that line and nobody is insured
     * twice. That is why a REAL borrower can be used here where an invented one could not.
     *
     * <p>Header only when the scheme has no members yet, which is a state the set-up form cannot
     * produce.
     */
    String renderTemplate(String policyNumber);
}
