package tz.co.nlolo.lifeplatform.policy.api;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/**
 * A lender's monthly exits file: the loans that ended, and the cover that ends with them.
 *
 * <p>The other half of {@link EnrolmentApi}. Enrolment files add borrowers and say nothing
 * about loans that ended, so without this channel no refund and no clawback can ever fire and
 * a repaid borrower stays insured for a debt that no longer exists (spec §2.12). Monthly, per
 * the client's answer of 2026-09-22.
 *
 * <p>Same shape as enrolment throughout, deliberately: propose, judge every row, take NOBODY
 * off cover until a different staff user accepts, one file in flight per scheme, and a report
 * that goes back naming every row that was refused.
 *
 * <p>The control matters at least as much here. A wrongly accepted enrolment file insures
 * somebody who should not have been, and the excess premium is visible on an invoice. A
 * wrongly accepted exits file UNINSURES somebody who should have been, and nobody finds out
 * until a claim is refused.
 */
public interface ExitApi {

    /**
     * Read and judge a lender's exits file. Takes nobody off cover.
     *
     * @throws InvalidPolicyStateException if the scheme is not credit life, a file is already
     *     awaiting acceptance, or the file itself cannot be read at all
     */
    ExitSubmissionView submit(String policyNumber, InputStream file, String fileName,
                               String submittedBy);

    /**
     * Turn a judged file into actual exits. Must be a DIFFERENT user from the submitter.
     *
     * @throws InvalidPolicyStateException if the file is not pending, or the accepter
     *     submitted it
     */
    ExitSubmissionView accept(UUID submissionId, String acceptedBy);

    /** Abandon a pending file, freeing the scheme for a corrected one. */
    ExitSubmissionView withdraw(UUID submissionId, String withdrawnBy);

    ExitSubmissionView getSubmission(UUID submissionId);

    /** Every row in the lender's own line order. */
    List<ExitRowView> listRows(UUID submissionId);

    /**
     * Every exits file this scheme has had, newest first.
     *
     * <p>Same reason as the enrolment equivalent: every other operation here takes a
     * {@code submissionId} the caller is assumed to already hold, which is true of the upload
     * flow and false of anybody arriving at a scheme cold.
     *
     * <p>Empty, never an exception, for a scheme that has had none.
     */
    List<ExitSubmissionView> listSubmissions(String policyNumber);

    /** The report that goes back to the lender, as CSV. */
    String renderReport(UUID submissionId);
}
