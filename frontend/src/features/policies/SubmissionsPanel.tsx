import { AlertTriangle, Check, Download, Upload, X } from 'lucide-react';
import { useRef, useState } from 'react';
import type { SubmissionStatus } from '@/api/types';
import { ConfirmAct } from '@/components/ConfirmAct';
import { Panel } from '@/components/Panel';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/cn';
import { formatInstant } from '@/lib/dates';
import type { ApiError } from '@/lib/apiError';
import { acceptAttribute, submissionUploadSchema, type SubmissionKind } from './submissionUploadForm';

/**
 * One lender's file, whichever direction it runs in.
 *
 * The enrolment and exits views are structurally identical and differ in exactly one field —
 * `enrolledCount` where the other has `exitedCount` — so this panel takes the noun and the
 * accessor rather than being written twice. Anything that is genuinely different between the two
 * (what acceptance *does*, which formats are allowed) arrives as a prop, so the next divergence
 * between them is a new prop rather than a fork.
 *
 * **The composition is built around one sentence: a PENDING submission has changed nothing.**
 * The counts row therefore leads with what was refused rather than what succeeded, the accepted
 * count is rendered as an intention until acceptance rather than an achievement, and the act that
 * changes cover is a confirmation with its consequence spelled out, never a bare button.
 */

/**
 * The busy key for the blank file, which has no submission id of its own.
 *
 * A uuid can never collide with it, which is what lets one `downloading` slot serve both the
 * template and every report on the panel.
 */
const TEMPLATE = 'template';

export interface SubmissionRow {
  submissionId: string;
  status: SubmissionStatus;
  fileName: string;
  rowCount: number;
  /** enrolledCount or exitedCount — the caller says which, because they mean opposite things. */
  appliedCount: number;
  rejectedCount: number;
  submittedBy: string;
  submittedAt: string;
  acceptedBy?: string | null;
}

interface Props {
  kind: SubmissionKind;
  title: string;
  subtitle: string;
  /** "enrolled" / "exited" — the verb this file performs on acceptance. */
  appliedNoun: string;
  submissions: SubmissionRow[];
  loading: boolean;
  error: ApiError | null;
  onRetry: () => void;
  selectedId: string | null;
  onSelect: (submissionId: string | null) => void;
  onUpload: (file: File) => void;
  uploading: boolean;
  uploadError: ApiError | null;
  onAccept: (submissionId: string) => void;
  onWithdraw: (submissionId: string) => void;
  deciding: boolean;
  /** Fetches the lender's report and saves it. Rejects if the request fails. */
  onDownloadReport: (submissionId: string) => Promise<void>;
  /** Fetches the blank file to send the lender. */
  onDownloadTemplate: () => Promise<void>;
  /** The columns the lender must fill, and the formats. Rendered beside the template. */
  columnGuide: React.ReactNode;
  /** Rendered under the selected submission: the rows and their reasons. */
  children?: React.ReactNode;
}

export function SubmissionsPanel({
  kind,
  title,
  subtitle,
  appliedNoun,
  submissions,
  loading,
  error,
  onRetry,
  selectedId,
  onSelect,
  onUpload,
  uploading,
  uploadError,
  onAccept,
  onWithdraw,
  deciding,
  onDownloadReport,
  onDownloadTemplate,
  columnGuide,
  children,
}: Props) {
  const fileInput = useRef<HTMLInputElement>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  // Which report is in flight, and whether the last one failed. A download that silently does
  // nothing is the worst available outcome here -- the person assumes they have the file.
  const [downloading, setDownloading] = useState<string | null>(null);
  const [reportError, setReportError] = useState<string | null>(null);
  // The format guide is collapsed by default: somebody running this month's file already knows
  // the columns, and somebody onboarding a lender needs them once.
  const [guideOpen, setGuideOpen] = useState(false);
  const [confirming, setConfirming] = useState<{ id: string; act: 'accept' | 'withdraw' } | null>(
    null,
  );

  const pending = submissions.find((s) => s.status === 'PENDING') ?? null;

  function chooseFile(file: File | undefined) {
    if (!file) return;
    const parsed = submissionUploadSchema(kind).safeParse({ file });
    if (!parsed.success) {
      setFileError(parsed.error.issues[0]?.message ?? 'That file cannot be uploaded');
      return;
    }
    setFileError(null);
    onUpload(file);
  }

  /**
   * One busy slot and one error line for every file this panel hands out.
   *
   * `key` is a submission id for a report and the TEMPLATE sentinel for the blank file, so two
   * downloads cannot both claim the spinner and a failure lands under the thing that failed.
   */
  async function download(key: string, fetch: () => Promise<void>, what: string) {
    setReportError(null);
    setDownloading(key);
    try {
      await fetch();
    } catch {
      setReportError(`That ${what} could not be downloaded. Try again, or reload the page.`);
    } finally {
      setDownloading(null);
    }
  }

  return (
    <Panel title={title} subtitle={subtitle} emphasis={Boolean(pending)}>
      {/* The upload bar. Disabled while a file is already awaiting a decision, because the
          scheme permits only one in flight -- saying so here is kinder than a 409 afterwards. */}
      <div className="flex flex-wrap items-center gap-3 border-b border-border px-4 py-3">
        <input
          ref={fileInput}
          type="file"
          accept={acceptAttribute(kind)}
          className="sr-only"
          onChange={(e) => {
            chooseFile(e.target.files?.[0]);
            e.target.value = '';
          }}
        />
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={uploading || Boolean(pending)}
          onClick={() => fileInput.current?.click()}
        >
          <Upload className="size-4" aria-hidden />
          {uploading ? 'Uploading…' : `Upload ${kind === 'exits' ? 'exits file' : 'schedule'}`}
        </Button>
        <p className="text-xs text-muted-foreground">
          {pending
            ? 'A file is already waiting for a decision. Accept or withdraw it first.'
            : kind === 'exits'
              ? 'CSV. Takes nobody off cover until someone accepts it.'
              : 'CSV or XLSX. Enrols nobody until someone accepts it.'}
        </p>
      </div>

      {/* THE BLANK FILE THE LENDER FILLS IN, and the columns spelled out beside it.
          The platform used to refuse a malformed file with "use the template at
          credit-life-enrolment-sample.csv" and serve no such thing -- that file exists only in
          the repository, so the format reached a lender only because somebody described it in an
          email, and a column nobody agreed on is a file that bounces. */}
      <div className="flex flex-wrap items-center gap-x-4 gap-y-1 border-b border-border px-4 py-2">
        <button
          type="button"
          className="text-xs underline underline-offset-2 hover:text-foreground disabled:opacity-60"
          disabled={downloading === TEMPLATE}
          onClick={() => void download(TEMPLATE, onDownloadTemplate, 'template')}
        >
          <Download className="mr-1 inline size-3.5" aria-hidden />
          {downloading === TEMPLATE
            ? 'Preparing the template…'
            : `Blank ${kind === 'exits' ? 'exits file' : 'schedule'} to send the lender`}
        </button>
        <button
          type="button"
          className="text-xs text-muted-foreground underline underline-offset-2 hover:text-foreground"
          aria-expanded={guideOpen}
          onClick={() => setGuideOpen((open) => !open)}
        >
          {guideOpen ? 'Hide the columns' : 'What goes in it'}
        </button>
      </div>
      {guideOpen && <div className="border-b border-border px-4 py-3">{columnGuide}</div>}

      {fileError && (
        <p role="alert" className="border-b border-border px-4 py-2 text-xs text-status-danger-fg">
          {fileError}
        </p>
      )}
      {reportError && (
        <p role="alert" className="border-b border-border px-4 py-2 text-xs text-status-danger-fg">
          {reportError}
        </p>
      )}
      {uploadError && <ErrorPanel error={uploadError} />}

      {loading && <TableSkeleton rows={3} columns={4} />}
      {error && !loading && <ErrorPanel error={error} onRetry={onRetry} />}

      {!loading && !error && submissions.length === 0 && (
        <EmptyState
          title="No files yet"
          description={
            kind === 'exits'
              ? "Upload the lender's monthly exits file to take repaid loans off cover."
              : "Upload the lender's schedule to put borrowers on cover."
          }
        />
      )}

      {!loading && !error && submissions.length > 0 && (
        <ul className="divide-y divide-border">
          {submissions.map((s) => {
            const selected = s.submissionId === selectedId;
            const isPending = s.status === 'PENDING';
            return (
              <li key={s.submissionId}>
                <div
                  className={cn(
                    'px-4 py-3',
                    selected && 'bg-selected',
                    !selected && 'hover:bg-hover',
                  )}
                >
                  <div className="flex flex-wrap items-start justify-between gap-3">
                    <button
                      type="button"
                      className="min-w-0 flex-1 text-left"
                      aria-expanded={selected}
                      onClick={() => onSelect(selected ? null : s.submissionId)}
                    >
                      <span className="flex flex-wrap items-center gap-2">
                        <span className="truncate text-sm font-medium">{s.fileName}</span>
                        <StatusBadge kind="submission" value={s.status} />
                      </span>
                      <span className="mt-1 block text-xs text-muted-foreground">
                        {s.rowCount} {s.rowCount === 1 ? 'row' : 'rows'} ·{' '}
                        {formatInstant(s.submittedAt)}
                      </span>
                      <Handlers submittedBy={s.submittedBy} acceptedBy={s.acceptedBy ?? null} />
                    </button>

                    {isPending && (
                      <div className="flex shrink-0 gap-2">
                        <Button
                          type="button"
                          size="sm"
                          disabled={deciding}
                          onClick={() => setConfirming({ id: s.submissionId, act: 'accept' })}
                        >
                          <Check className="size-4" aria-hidden />
                          Accept
                        </Button>
                        <Button
                          type="button"
                          variant="outline"
                          size="sm"
                          disabled={deciding}
                          onClick={() => setConfirming({ id: s.submissionId, act: 'withdraw' })}
                        >
                          <X className="size-4" aria-hidden />
                          Withdraw
                        </Button>
                      </div>
                    )}
                  </div>

                  {/* The counts. Refused leads, because a refused row is a person without
                      insurance their lender may believe is covered -- it is the output of an
                      upload, not an error beside it. */}
                  <dl className="mt-2 flex flex-wrap items-baseline gap-x-6 gap-y-1">
                    <div className="flex items-baseline gap-1.5">
                      <dt className="text-xs text-muted-foreground">Refused</dt>
                      <dd
                        className={cn(
                          'text-sm font-semibold tabular-nums',
                          s.rejectedCount > 0 && 'text-status-danger-fg',
                        )}
                      >
                        {s.rejectedCount}
                      </dd>
                      {s.rejectedCount > 0 && (
                        <AlertTriangle className="size-3.5 text-status-danger-fg" aria-hidden />
                      )}
                      {/* A count on its own does not tell anybody anything they can act on. The
                          reasons are per row and live in the table below, so when a file with
                          refusals is collapsed this says so and opens it -- rather than leaving
                          somebody to discover that the number was clickable. */}
                      {s.rejectedCount > 0 && !selected && (
                        <button
                          type="button"
                          className="text-xs text-status-danger-fg underline underline-offset-2"
                          onClick={() => onSelect(s.submissionId)}
                        >
                          See why
                        </button>
                      )}
                    </div>
                    <div className="flex items-baseline gap-1.5">
                      <dt className="text-xs text-muted-foreground">
                        {isPending
                          ? `Would be ${appliedNoun}`
                          : appliedNoun.charAt(0).toUpperCase() + appliedNoun.slice(1)}
                      </dt>
                      <dd className="text-sm font-semibold tabular-nums">{s.appliedCount}</dd>
                    </div>
                    {/* A button, not a link. The report is behind the same bearer token as
                        everything else on this page, and an anchor carries none -- see
                        `downloadEnrolmentReport` for what that actually produced. */}
                    <button
                      type="button"
                      className="text-xs underline underline-offset-2 hover:text-foreground disabled:opacity-60"
                      disabled={downloading === s.submissionId}
                      onClick={() =>
                        void download(s.submissionId, () => onDownloadReport(s.submissionId), 'report')
                      }
                    >
                      <Download className="mr-1 inline size-3.5" aria-hidden />
                      {downloading === s.submissionId
                        ? 'Preparing the report…'
                        : 'Report for the lender'}
                    </button>
                  </dl>

                  {isPending && (
                    <p className="mt-2 text-xs text-status-pending-fg">
                      Nothing has happened yet. {s.rowCount}{' '}
                      {s.rowCount === 1 ? 'row was' : 'rows were'} judged and recorded; a second
                      person must accept this file before anyone is {appliedNoun}.
                    </p>
                  )}
                </div>

                {selected && children}
              </li>
            );
          })}
        </ul>
      )}

      {confirming && (
        <ConfirmAct
          heading={
            confirming.act === 'accept'
              ? `Accept this file?`
              : `Withdraw this file?`
          }
          consequence={
            confirming.act === 'accept' ? (
              <>
                The rows that passed will be <strong>{appliedNoun}</strong>. Refused rows are not,
                and the lender learns which from the report.
              </>
            ) : (
              <>
                Nothing on this file takes effect, and the scheme is free to receive a corrected
                one.
              </>
            )
          }
          reversal={
            confirming.act === 'accept'
              ? kind === 'exits'
                ? 'Reversing an exit means re-enrolling the borrower on a later file.'
                : 'Reversing an enrolment means exiting the borrower on a later file.'
              : 'A withdrawn file cannot be accepted afterwards; upload the corrected file instead.'
          }
          confirmLabel={confirming.act === 'accept' ? 'Accept' : 'Withdraw'}
          tone={confirming.act === 'accept' ? 'primary' : 'danger'}
          busy={deciding}
          onConfirm={() => {
            if (confirming.act === 'accept') onAccept(confirming.id);
            else onWithdraw(confirming.id);
            setConfirming(null);
          }}
          onCancel={() => setConfirming(null)}
        />
      )}
    </Panel>
  );
}


/**
 * Who handled this file, and whether that was two different people.
 *
 * <p><b>The raw identifiers are shown short and monospaced rather than resolved to names,
 * because the platform cannot resolve them.</b> These are Keycloak subjects; there is no
 * staff-name lookup anywhere on this platform, and the audit log itself has no actor column.
 * Inventing a name here would be the one thing this console refuses to do. The house treatment
 * for an identifier is monospace — see `DisclosurePanel`'s `recordedBy` — and the full value is
 * on the title attribute for anyone who needs to paste it into a support ticket.
 *
 * <p><b>What this actually answers is the separation-of-duties question</b>, which is the only
 * reason either field exists: acceptance is what creates cover, and it must be a different person
 * from the one who uploaded the file. Two 36-character UUIDs side by side answer that terribly —
 * a reader has to diff them by eye — so the comparison is made here and stated in words. The
 * backend refuses a same-person acceptance, so the mismatch branch should be unreachable; it is
 * rendered loudly rather than omitted, because a control that has silently stopped working is
 * exactly the thing a person needs told.
 */
function Handlers({
  submittedBy,
  acceptedBy,
}: {
  submittedBy: string;
  acceptedBy: string | null;
}) {
  const short = (id: string) => (id.length > 8 ? id.slice(0, 8) : id);
  const samePerson = acceptedBy != null && acceptedBy === submittedBy;

  return (
    <span className="mt-1 block text-xs text-muted-foreground">
      Uploaded by{' '}
      <span className="font-mono" title={submittedBy}>
        {short(submittedBy)}
      </span>
      {acceptedBy ? (
        <>
          , accepted by{' '}
          <span className="font-mono" title={acceptedBy}>
            {short(acceptedBy)}
          </span>
          {samePerson ? (
            <span className="ml-1.5 text-status-danger-fg">
              — the same person. Acceptance is meant to need a second.
            </span>
          ) : (
            <span className="ml-1.5">— two people, as it should be.</span>
          )}
        </>
      ) : (
        <span className="ml-1.5">— awaiting a second person.</span>
      )}
    </span>
  );
}
