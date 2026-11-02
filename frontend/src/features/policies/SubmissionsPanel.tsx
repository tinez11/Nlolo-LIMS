import { ChevronDown, Download, FileSpreadsheet, FileText, Upload } from 'lucide-react';
import { useRef, useState, type ReactNode } from 'react';
import { useAuth } from 'react-oidc-context';
import { can } from '@/auth/access';
import { readIdentity } from '@/auth/claims';
import type { SubmissionStatus } from '@/api/types';
import { ConfirmAct } from '@/components/ConfirmAct';
import { Panel } from '@/components/Panel';
import { StaffName } from '@/components/StaffName';
import { StatusBadge } from '@/components/StatusBadge';
import { InlineError } from '@/components/InlineError';
import { EmptyState, ErrorPanel, TableSkeleton } from '@/components/states';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/cn';
import { formatInstant } from '@/lib/dates';
import type { ApiError } from '@/lib/apiError';
import { acceptAttribute, submissionUploadSchema, type SubmissionKind } from './submissionUploadForm';

/**
 * A lender's monthly files, whichever direction they run in (simplified 2026-11-02).
 *
 * One toolbar -- upload, the template, what goes in it -- then the file waiting for a decision, if there is one, and
 * the files before it. Each file says what it did as a bar and two counts rather than a paragraph: how many rows went
 * through and how many were refused. A PENDING file has changed nothing, so its count reads "would be"; the act that
 * changes cover is a confirmation with its consequence in the file's own figures.
 */

/** Busy keys for the blank files, which have no submission id of their own. */
const TEMPLATE = 'template';
const TEMPLATE_CSV = 'template-csv';

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
  /** Why the last accept or withdraw was refused -- the uploader accepting their own file, most often. */
  decisionError?: ApiError | null;
  /** Fetches the lender's report and saves it. Rejects if the request fails. */
  onDownloadReport: (submissionId: string) => Promise<void>;
  /** The quotation the lender is sent for a file, as a PDF (joining files only, 2026-11-02). */
  onDownloadQuotation?: (submissionId: string) => Promise<void>;
  /** Fetches the spreadsheet template to send the lender. */
  onDownloadTemplate: () => Promise<void>;
  /** The CSV form of the same template, offered second and quieter: Excel rewrites the dates in a CSV. */
  onDownloadTemplateCsv?: () => Promise<void>;
  /** The columns the lender must fill. Folded away until asked for. */
  columnGuide: ReactNode;
  /** What accepting this file does, in its real figures, where the page has priced it. */
  acceptConsequence?: (submissionId: string) => ReactNode | null;
  /** Why no file can be sent yet -- a credit-life scheme takes none until its yearly agreement prices it. */
  blockedBy?: string | null;
  /** Rendered under the open file: its price and its rows. */
  children?: ReactNode;
}

export function SubmissionsPanel({
  kind,
  title,
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
  decisionError = null,
  onDownloadReport,
  onDownloadQuotation,
  onDownloadTemplate,
  onDownloadTemplateCsv,
  columnGuide,
  acceptConsequence,
  blockedBy = null,
  children,
}: Props) {
  const fileInput = useRef<HTMLInputElement>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  // A download that silently does nothing is the worst outcome here -- the person assumes they have the file.
  const [downloading, setDownloading] = useState<string | null>(null);
  const [downloadError, setDownloadError] = useState<string | null>(null);
  const [guideOpen, setGuideOpen] = useState(false);
  const [confirming, setConfirming] = useState<{ id: string; act: 'accept' | 'withdraw' } | null>(null);

  const pending = submissions.find((s) => s.status === 'PENDING') ?? null;
  // Acceptance must be a second person: the one who sent the file is told so up front, not refused after a click.
  const viewer = readIdentity(useAuth().user?.access_token).subject;
  const sentByViewer = pending != null && viewer != null && pending.submittedBy === viewer;
  const mayDecide = can('schemes.enrolments');
  const earlier = submissions.filter((s) => s !== pending);
  const fileWord = kind === 'exits' ? 'loans-ending file' : 'joining file';

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

  async function download(key: string, fetch: () => Promise<void>, what: string) {
    setDownloadError(null);
    setDownloading(key);
    try {
      await fetch();
    } catch {
      setDownloadError(`The ${what} could not be downloaded. Try again.`);
    } finally {
      setDownloading(null);
    }
  }

  const quoteKey = (s: SubmissionRow) => `quotation-${s.submissionId}`;
  const reportButton = (s: SubmissionRow) => (
    <span className="flex flex-wrap gap-1">
      {/* The quotation the lender signs, like an individual's (2026-11-02). None on a withdrawn file: it charges
          nothing. */}
      {onDownloadQuotation && s.status !== 'WITHDRAWN' && (
        <Button
          type="button"
          size="sm"
          variant="outline"
          disabled={downloading === quoteKey(s)}
          onClick={() => void download(quoteKey(s), () => onDownloadQuotation(s.submissionId), 'quotation')}
        >
          <FileText className="size-4" aria-hidden />
          {downloading === quoteKey(s) ? 'Preparing…' : 'Quotation'}
        </Button>
      )}
      {/* A button, not a link: the report is behind the same bearer token as the rest of the page. */}
      <Button
        type="button"
        size="sm"
        variant="ghost"
        disabled={downloading === s.submissionId}
        onClick={() => void download(s.submissionId, () => onDownloadReport(s.submissionId), 'report')}
      >
        <Download className="size-4" aria-hidden />
        {downloading === s.submissionId ? 'Preparing…' : 'Report for the lender'}
      </Button>
    </span>
  );

  return (
    <Panel title={title}>
      {/* The toolbar: send a file, give the lender the blank one, see its columns. */}
      <div className="flex flex-wrap items-center gap-2 border-b border-border px-4 py-3">
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
          size="sm"
          variant={pending || blockedBy ? 'outline' : 'primary'}
          disabled={uploading || Boolean(pending) || Boolean(blockedBy)}
          onClick={() => fileInput.current?.click()}
        >
          <Upload className="size-4" aria-hidden />
          {uploading ? 'Uploading…' : `Upload ${fileWord}`}
        </Button>
        <Button
          type="button"
          size="sm"
          variant="outline"
          disabled={downloading === TEMPLATE || Boolean(blockedBy)}
          onClick={() => void download(TEMPLATE, onDownloadTemplate, 'template')}
        >
          <FileSpreadsheet className="size-4" aria-hidden />
          {downloading === TEMPLATE ? 'Preparing…' : 'Download template'}
        </Button>
        {onDownloadTemplateCsv && (
          <button
            type="button"
            className="px-1 text-xs text-muted-foreground underline underline-offset-2 hover:text-foreground disabled:opacity-50"
            disabled={downloading === TEMPLATE_CSV || Boolean(blockedBy)}
            onClick={() => void download(TEMPLATE_CSV, onDownloadTemplateCsv, 'template')}
          >
            {downloading === TEMPLATE_CSV ? 'Preparing…' : 'CSV'}
          </button>
        )}
        <Button
          type="button"
          size="sm"
          variant="ghost"
          className="ml-auto"
          aria-expanded={guideOpen}
          onClick={() => setGuideOpen((open) => !open)}
        >
          What goes in it
          <ChevronDown className={cn('size-4 transition-transform', guideOpen && 'rotate-180')} aria-hidden />
        </Button>
      </div>
      {(blockedBy || pending) && (
        <p className="border-b border-border bg-status-warning-bg px-4 py-2 text-sm text-status-warning-fg">
          {blockedBy ??
            (sentByViewer
              ? 'Your file below is waiting for a colleague to accept it. The next file can be sent once it is decided.'
              : 'A file below is waiting for acceptance. The next file can be sent once it is decided.')}
        </p>
      )}
      {guideOpen && <div className="border-b border-border px-4 py-3">{columnGuide}</div>}

      {(fileError || downloadError) && (
        <p role="alert" className="border-b border-border px-4 py-2 text-sm text-status-danger-fg">
          {fileError ?? downloadError}
        </p>
      )}
      {/* InlineError: these are refused ACTS, and their 409s carry the server's reason. */}
      {uploadError && (
        <div className="border-b border-border px-4 py-3">
          <InlineError error={uploadError} lead="The file was not uploaded" />
        </div>
      )}
      {decisionError && (
        <div className="border-b border-border px-4 py-3">
          <InlineError error={decisionError} lead="The file was not decided" />
        </div>
      )}

      {loading && <TableSkeleton rows={3} columns={4} />}
      {error && !loading && <ErrorPanel error={error} onRetry={onRetry} />}

      {!loading && !error && submissions.length === 0 && (
        <EmptyState
          title="No files yet"
          description={
            kind === 'exits'
              ? 'Upload the lender’s loans-ending file to take finished loans off cover.'
              : 'Upload the lender’s joining file to put borrowers on cover.'
          }
        />
      )}

      {/* THE FILE WAITING FOR A DECISION, first and open: it blocks the next upload and covers nobody yet. */}
      {!loading && !error && pending && (
        <section aria-label="Waiting for acceptance" className="border-b border-border">
          <div className="space-y-3 bg-status-pending-bg/40 px-4 py-4">
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div className="min-w-0">
                <p className="flex flex-wrap items-center gap-2">
                  <span className="truncate text-sm font-semibold">{pending.fileName}</span>
                  <StatusBadge kind="submission" value={pending.status} />
                </p>
                <p className="mt-0.5 text-xs text-muted-foreground">
                  Sent {formatInstant(pending.submittedAt)} by <StaffName subject={pending.submittedBy} />
                </p>
              </div>
              {mayDecide && (
              <div className="flex shrink-0 flex-wrap gap-2">
                {!sentByViewer && (
                  <Button
                    type="button"
                    size="sm"
                    variant="primary"
                    disabled={deciding}
                    onClick={() => setConfirming({ id: pending.submissionId, act: 'accept' })}
                  >
                    Accept
                  </Button>
                )}
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  disabled={deciding}
                  onClick={() => setConfirming({ id: pending.submissionId, act: 'withdraw' })}
                >
                  Withdraw
                </Button>
              </div>
              )}
            </div>
            <Outcome row={pending} appliedNoun={appliedNoun} />
            <p className="text-sm text-status-pending-fg">
              {sentByViewer
                ? `You sent this file, so a colleague must accept it. Nobody is ${appliedNoun} until they do.`
                : `Nobody is ${appliedNoun} until a second person accepts this file.`}
            </p>
            <div className="-ml-3">{reportButton(pending)}</div>
            {confirming?.id === pending.submissionId && (
              <ConfirmAct
                heading={confirming.act === 'accept' ? 'Accept this file?' : 'Withdraw this file?'}
                consequence={
                  confirming.act === 'accept' ? (
                    acceptConsequence?.(confirming.id) ?? (
                      <>
                        The rows that passed are <strong>{appliedNoun}</strong>. Refused rows are not, and the report
                        tells the lender which.
                      </>
                    )
                  ) : (
                    <>Nothing on this file takes effect, and a corrected file can be sent.</>
                  )
                }
                reversal={
                  confirming.act === 'accept'
                    ? kind === 'exits'
                      ? 'To undo an exit, enrol the borrower again on a later file.'
                      : 'To undo an enrolment, exit the borrower on a later file.'
                    : 'A withdrawn file cannot be accepted later; upload the corrected file instead.'
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
          </div>
          {selectedId === pending.submissionId ? (
            children
          ) : (
            <button
              type="button"
              className="w-full border-t border-border px-4 py-2 text-left text-sm underline underline-offset-2 hover:bg-hover"
              onClick={() => onSelect(pending.submissionId)}
            >
              Show the price and every row
            </button>
          )}
        </section>
      )}

      {/* THE FILES BEFORE IT: one line each, opened on a click. Withdrawn files stay, dimmed: they are the record of
          what the lender sent and what was refused. */}
      {!loading && !error && earlier.length > 0 && (
        <ul className="divide-y divide-border" aria-label={pending ? 'Earlier files' : 'Files'}>
          {earlier.map((s) => {
            const open = s.submissionId === selectedId;
            return (
              <li key={s.submissionId}>
                <div
                  className={cn(
                    'grid grid-cols-1 items-center gap-x-4 gap-y-2 px-4 py-3 lg:grid-cols-[minmax(0,1fr)_16rem_auto]',
                    open ? 'bg-selected' : 'hover:bg-hover',
                    s.status === 'WITHDRAWN' && !open && 'opacity-60',
                  )}
                >
                  <button
                    type="button"
                    className="flex min-w-0 items-center gap-2 text-left"
                    aria-expanded={open}
                    onClick={() => onSelect(open ? null : s.submissionId)}
                  >
                    <ChevronDown
                      className={cn('size-4 shrink-0 text-muted-foreground transition-transform', !open && '-rotate-90')}
                      aria-hidden
                    />
                    <span className="min-w-0">
                      <span className="flex flex-wrap items-center gap-2">
                        <span className="truncate text-sm font-medium">{s.fileName}</span>
                        <StatusBadge kind="submission" value={s.status} />
                      </span>
                      <span className="mt-0.5 block text-xs text-muted-foreground">
                        {formatInstant(s.submittedAt)}
                      </span>
                    </span>
                  </button>
                  <Outcome row={s} appliedNoun={appliedNoun} compact />
                  <div className="justify-self-start lg:justify-self-end">{reportButton(s)}</div>
                </div>
                {open && (
                  <>
                    <p className="border-t border-border px-4 py-2 text-xs text-muted-foreground">
                      <Handlers submittedBy={s.submittedBy} acceptedBy={s.acceptedBy ?? null} />
                    </p>
                    {children}
                  </>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </Panel>
  );
}

/**
 * What a file did, drawn: rows that went through and rows refused, as one bar and two counts. A pending file's count
 * is an intention ("would be"), never an achievement.
 */
function Outcome({ row, appliedNoun, compact = false }: { row: SubmissionRow; appliedNoun: string; compact?: boolean }) {
  const pending = row.status === 'PENDING';
  const withdrawn = row.status === 'WITHDRAWN';
  // The applied count is filled at acceptance, so an undecided file's passing rows are the rows not refused.
  const applied = pending || withdrawn ? Math.max(0, row.rowCount - row.rejectedCount) : row.appliedCount;
  const total = applied + row.rejectedCount;
  const passed = total === 0 ? 0 : (applied / total) * 100;
  const passedLabel = pending ? `would be ${appliedNoun}` : withdrawn ? 'passed' : appliedNoun;

  return (
    <div className={cn('min-w-0', !compact && 'max-w-md')}>
      <div className="flex h-2 overflow-hidden rounded-full bg-control" aria-hidden>
        {applied > 0 && (
          <span
            className={cn(
              'h-full',
              withdrawn ? 'bg-muted-foreground/50' : pending ? 'bg-status-pending-fg' : 'bg-status-success-fg',
            )}
            style={{ width: `${passed}%` }}
          />
        )}
        {row.rejectedCount > 0 && <span className="h-full flex-1 bg-status-danger-fg" />}
      </div>
      <p className="mt-1.5 flex flex-wrap gap-x-3 text-xs">
        <span>
          <span className="font-semibold tabular-nums">{applied}</span>{' '}
          <span className="text-muted-foreground">{passedLabel}</span>
        </span>
        <span className={cn(row.rejectedCount > 0 && 'text-status-danger-fg')}>
          <span className="font-semibold tabular-nums">{row.rejectedCount}</span> refused
        </span>
        {!compact && <span className="text-muted-foreground">of {row.rowCount} rows</span>}
      </p>
    </div>
  );
}

/**
 * Who handled the file, and whether that was two people -- acceptance creates cover and must be a second person. The
 * backend refuses a same-person acceptance, so that branch should be unreachable; it is said loudly if it ever is not.
 */
function Handlers({ submittedBy, acceptedBy }: { submittedBy: string; acceptedBy: string | null }) {
  const samePerson = acceptedBy != null && acceptedBy === submittedBy;
  return (
    <>
      Sent by <StaffName subject={submittedBy} />
      {acceptedBy && (
        <>
          {' · '}accepted by <StaffName subject={acceptedBy} />
          {samePerson && (
            <span className="ml-1.5 text-status-danger-fg">— the same person; acceptance needs a second.</span>
          )}
        </>
      )}
    </>
  );
}
