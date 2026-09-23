import { get, post } from '@/lib/http';
import type {
  EnrolmentRowView,
  EnrolmentSubmissionView,
  ExitRowView,
  ExitSubmissionView,
} from './types';

/**
 * A credit-life scheme's two monthly files: the borrowers joining, and the loans ending.
 *
 * Hand-written over the generated types for the same reason the rest of `src/api` is: no spec
 * declares an `operationId`, so a generator would invent names for every operation, and the Axios
 * layer owns error normalisation.
 *
 * <b>The two file kinds are deliberately NOT merged behind one parameterised client.</b> Their
 * wire shapes are structurally identical and differ in exactly one field — `enrolledCount` where
 * the other has `exitedCount` — but they mean opposite things: one puts borrowers on cover and
 * charges a premium, the other takes them off and may refund one. A shared `submissions(kind)`
 * helper would make the next divergence a branch instead of a function.
 */

/* ------------------------------------------------------------------ enrolment */

/**
 * `POST /credit-life-schemes/{policyNumber}/enrolments` — multipart.
 *
 * **Enrols nobody.** Every row is parsed, judged and recorded with its outcome, and a second
 * staff user must accept the submission before any cover exists. The 201 that comes back is a
 * judgement, not an enrolment.
 */
export function uploadEnrolmentFile(
  policyNumber: string,
  file: File,
): Promise<EnrolmentSubmissionView> {
  const form = new FormData();
  form.append('file', file);
  return post<EnrolmentSubmissionView>(
    `/credit-life-schemes/${encodeURIComponent(policyNumber)}/enrolments`,
    form,
  );
}

/** `GET /credit-life-schemes/{policyNumber}/enrolments` — a bare unpaged array, newest first. */
export function listEnrolmentSubmissions(
  policyNumber: string,
): Promise<EnrolmentSubmissionView[]> {
  return get<EnrolmentSubmissionView[]>(
    `/credit-life-schemes/${encodeURIComponent(policyNumber)}/enrolments`,
  );
}

/** `GET .../enrolments/{submissionId}/rows` — one entry per row of the uploaded file. */
export function listEnrolmentRows(
  policyNumber: string,
  submissionId: string,
): Promise<EnrolmentRowView[]> {
  return get<EnrolmentRowView[]>(
    `/credit-life-schemes/${encodeURIComponent(policyNumber)}/enrolments/${encodeURIComponent(submissionId)}/rows`,
  );
}

/** `POST .../enrolments/{submissionId}/acceptance` — the act that creates cover. */
export function acceptEnrolmentSubmission(
  policyNumber: string,
  submissionId: string,
): Promise<EnrolmentSubmissionView> {
  return post<EnrolmentSubmissionView>(
    `/credit-life-schemes/${encodeURIComponent(policyNumber)}/enrolments/${encodeURIComponent(submissionId)}/acceptance`,
    {},
  );
}

/** `POST .../enrolments/{submissionId}/withdrawal` — frees the scheme for a corrected file. */
export function withdrawEnrolmentSubmission(
  policyNumber: string,
  submissionId: string,
): Promise<EnrolmentSubmissionView> {
  return post<EnrolmentSubmissionView>(
    `/credit-life-schemes/${encodeURIComponent(policyNumber)}/enrolments/${encodeURIComponent(submissionId)}/withdrawal`,
    {},
  );
}

/* ---------------------------------------------------------------------- exits */

/**
 * `POST /credit-life-schemes/{policyNumber}/exits` — multipart, CSV only.
 *
 * **Takes nobody off cover.** Same propose-then-accept shape as the enrolment file, and the same
 * warning applies in the opposite direction: the 201 records a judgement, and acceptance is what
 * ends cover, refunds unearned premium and claws back the commission it earned.
 */
export function uploadExitsFile(policyNumber: string, file: File): Promise<ExitSubmissionView> {
  const form = new FormData();
  form.append('file', file);
  return post<ExitSubmissionView>(
    `/credit-life-schemes/${encodeURIComponent(policyNumber)}/exits`,
    form,
  );
}

/** `GET /credit-life-schemes/{policyNumber}/exits` — a bare unpaged array, newest first. */
export function listExitSubmissions(policyNumber: string): Promise<ExitSubmissionView[]> {
  return get<ExitSubmissionView[]>(
    `/credit-life-schemes/${encodeURIComponent(policyNumber)}/exits`,
  );
}

/** `GET .../exits/{submissionId}/rows` — one entry per row of the uploaded file. */
export function listExitRows(
  policyNumber: string,
  submissionId: string,
): Promise<ExitRowView[]> {
  return get<ExitRowView[]>(
    `/credit-life-schemes/${encodeURIComponent(policyNumber)}/exits/${encodeURIComponent(submissionId)}/rows`,
  );
}

/** `POST .../exits/{submissionId}/acceptance` — the act that ends cover. */
export function acceptExitSubmission(
  policyNumber: string,
  submissionId: string,
): Promise<ExitSubmissionView> {
  return post<ExitSubmissionView>(
    `/credit-life-schemes/${encodeURIComponent(policyNumber)}/exits/${encodeURIComponent(submissionId)}/acceptance`,
    {},
  );
}

/** `POST .../exits/{submissionId}/withdrawal`. */
export function withdrawExitSubmission(
  policyNumber: string,
  submissionId: string,
): Promise<ExitSubmissionView> {
  return post<ExitSubmissionView>(
    `/credit-life-schemes/${encodeURIComponent(policyNumber)}/exits/${encodeURIComponent(submissionId)}/withdrawal`,
    {},
  );
}

/* --------------------------------------------------------------------- report */

/**
 * The report path for either file kind.
 *
 * Returned as a URL rather than fetched, because the report is a download the browser handles —
 * and because it is the deliverable rather than a courtesy: `member_reference` is its first data
 * column, and that column is the only place a lender ever learns the reference the insurer
 * minted for each borrower.
 */
export function enrolmentReportPath(policyNumber: string, submissionId: string): string {
  return `/credit-life-schemes/${encodeURIComponent(policyNumber)}/enrolments/${encodeURIComponent(submissionId)}/report`;
}

export function exitsReportPath(policyNumber: string, submissionId: string): string {
  return `/credit-life-schemes/${encodeURIComponent(policyNumber)}/exits/${encodeURIComponent(submissionId)}/report`;
}
