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
 * `POST /credit-life-schemes/{policyNumber}/exits` — multipart, CSV or XLSX.
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
 * The report that goes back to the lender, for either file kind.
 *
 * <b>This is the deliverable rather than a courtesy.</b> `member_reference` is its first data
 * column, and that column is the only place a lender ever learns the reference the insurer minted
 * for each borrower; the refusal reasons beside it are the only place they learn which of their
 * customers is uninsured.
 *
 * <b>Fetched as a blob, not linked to.</b> It was an `<a href download>` pointing straight at the
 * endpoint, which could not work and did not: this SPA keeps its tokens in memory, so an anchor
 * is an anonymous request. Prefixed it is a 401; unprefixed — which is how it shipped — the dev
 * server answers the unknown path with `index.html` and the browser saves the console's own HTML
 * under the name the person expected. They get a file. It is the wrong file. They forward it to
 * the lender. `downloadClaimEvidence` established the authenticated-blob pattern; this follows it.
 *
 * The file name mirrors the server's own `Content-Disposition`, which an XHR does not surface to
 * the page.
 */
export function downloadEnrolmentReport(policyNumber: string, submissionId: string): Promise<Blob> {
  return get<Blob>(
    `/credit-life-schemes/${encodeURIComponent(policyNumber)}/enrolments/${encodeURIComponent(submissionId)}/report`,
    // Accept: text/csv OVERRIDES the client's default application/json. The endpoint declares
    // produces = "text/csv", so Spring answered a plain 406 to every request this console made --
    // the download failed for a reason that had nothing to do with the report.
    { responseType: 'blob', headers: { Accept: 'text/csv' } },
  );
}

export function enrolmentReportFileName(submissionId: string): string {
  return `enrolment-report-${submissionId}.csv`;
}

export function downloadExitsReport(policyNumber: string, submissionId: string): Promise<Blob> {
  return get<Blob>(
    `/credit-life-schemes/${encodeURIComponent(policyNumber)}/exits/${encodeURIComponent(submissionId)}/report`,
    // Accept: text/csv OVERRIDES the client's default application/json. The endpoint declares
    // produces = "text/csv", so Spring answered a plain 406 to every request this console made --
    // the download failed for a reason that had nothing to do with the report.
    { responseType: 'blob', headers: { Accept: 'text/csv' } },
  );
}

export function exitsReportFileName(submissionId: string): string {
  return `exits-report-${submissionId}.csv`;
}

/* ------------------------------------------------------------------- template */

/**
 * The blank file a lender is given.
 *
 * <b>The platform refused files with "Use the template at credit-life-enrolment-sample.csv"
 * while serving no such thing.</b> That file lives in the repository's spec folder, which no
 * staff user and certainly no lender can reach — so a person reading the refusal had been told
 * to use a document they had no way to obtain, and the format reached the lenders only because
 * somebody described it in an email.
 *
 * Not scoped to a scheme: the columns belong to the product, not to one lender's book. The
 * header is generated by the same parser that judges the file, so the two cannot drift.
 */
export function downloadEnrolmentTemplate(policyNumber: string): Promise<Blob> {
  return get<Blob>(`/credit-life-schemes/${encodeURIComponent(policyNumber)}/template`, {
    responseType: 'blob',
    headers: { Accept: 'text/csv' },
  });
}

export function downloadExitsTemplate(policyNumber: string): Promise<Blob> {
  return get<Blob>(`/credit-life-schemes/${encodeURIComponent(policyNumber)}/exits-template`, {
    responseType: 'blob',
    headers: { Accept: 'text/csv' },
  });
}

/**
 * The same template as a spreadsheet, and the one to send a lender who works in Excel.
 *
 * <b>A CSV template cannot survive Excel.</b> Two real files came back with every date rewritten —
 * 1-Sep-00, then 9/1/2000 after being told the format — including the worked example whose whole
 * job was to show it. In a spreadsheet a date is a typed cell, not text: Excel round-trips it
 * whatever it displays, and the upload reads it back as ISO.
 */
export function downloadEnrolmentTemplateXlsx(policyNumber: string): Promise<Blob> {
  return get<Blob>(`/credit-life-schemes/${encodeURIComponent(policyNumber)}/template.xlsx`, {
    responseType: 'blob',
    headers: {
      Accept: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    },
  });
}

export function enrolmentTemplateXlsxFileName(policyNumber: string): string {
  return `enrolment-template-${policyNumber}.xlsx`;
}
export function enrolmentTemplateFileName(policyNumber: string): string {
  return `enrolment-template-${policyNumber}.csv`;
}

/**
 * The exits file as a spreadsheet — the one to send a lender who works in Excel.
 *
 * Carries the same "How to fill this in" sheet the enrolment workbook does, which is the only way
 * the rules reach a lender: the console's column guide is on a staff screen they never see, and
 * nothing delivers it for them.
 */
export function downloadExitsTemplateXlsx(policyNumber: string): Promise<Blob> {
  return get<Blob>(`/credit-life-schemes/${encodeURIComponent(policyNumber)}/exits-template.xlsx`, {
    responseType: 'blob',
    headers: {
      Accept: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    },
  });
}

export function exitsTemplateXlsxFileName(policyNumber: string): string {
  return `exits-template-${policyNumber}.xlsx`;
}

export function exitsTemplateFileName(policyNumber: string): string {
  return `exits-template-${policyNumber}.csv`;
}
