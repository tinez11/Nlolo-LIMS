import { Check } from 'lucide-react';
import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { useBreadcrumb, useSearchParamsKeepingTrail } from '@/lib/cameFrom';
import { Link, useParams } from 'react-router-dom';
import {
  downloadEnrolmentQuotation,
  downloadEnrolmentReport,
  downloadEnrolmentTemplate,
  downloadEnrolmentTemplateXlsx,
  downloadExitsReport,
  downloadExitsTemplate,
  downloadExitsTemplateXlsx,
  enrolmentQuotationFileName,
  enrolmentReportFileName,
  enrolmentTemplateFileName,
  enrolmentTemplateXlsxFileName,
  exitsReportFileName,
  exitsTemplateFileName,
  exitsTemplateXlsxFileName,
} from '@/api/creditLife';
import type { CreditLifeAgreement, EnrolmentRowView, ExitRowView, SubmissionStatus } from '@/api/types';
import { DetailLayout } from '@/components/DetailLayout';
import { Field } from '@/components/Field';
import { PageHeader } from '@/components/PageHeader';
import { Panel } from '@/components/Panel';
import { PartyName } from '@/components/PartyName';
import { RecordTabs, type TabDef } from '@/components/RecordTabs';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, LoadingBlock, TableSkeleton } from '@/components/states';
import { Button } from '@/components/ui/button';
import type { ApiError } from '@/lib/apiError';
import { cn } from '@/lib/cn';
import { formatDate, formatInstant } from '@/lib/dates';
import { humanizeStatus } from '@/lib/status';
import { saveBlob } from '@/lib/download';
import { NO_VALUE, formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import {
  selectAgreements,
  selectDeciding,
  selectEnrolmentRows,
  selectEnrolments,
  selectExitRows,
  selectExits,
  selectQuotation,
  selectUploading,
  useCreditLifeStore,
} from '@/store/creditLifeStore';
import { selectDetail, selectScheme, usePolicyStore } from '@/store/policyStore';
import { ProductName } from '@/components/ProductName';
import { CommissionEarnerPanel, CommissionPanel } from './CommissionPanel';
import { useCommission } from './useCommission';
import { CreditLifeAgreementsPanel } from './CreditLifeAgreementsPanel';
import { EnrolmentQuotation } from './EnrolmentQuotation';
import { agreementOn, isPositive, pricingRuleInWords, trimZeros } from './creditLifeAgreementForm';
import { FreeCoverLimitEditor } from './FreeCoverLimitEditor';
import { SubmissionsPanel, type SubmissionRow } from './SubmissionsPanel';

/**
 * A credit-life scheme: a lender's book of borrowers, run one monthly file at a time.
 *
 * Laid out like the policy record (2026-11-02): status beside the title, the figures the scheme exists for on the
 * figures band, then the three steps a lender's month runs through -- the yearly agreement, borrowers joining, loans
 * ending -- with the one thing to do next, and the work itself in tabs. The monthly files lead, because they are what
 * somebody opens this page to do.
 *
 * The one thing every element is arranged to prevent is reading an uploaded file as a finished one: a file that has
 * not been accepted has enrolled nobody and exited nobody.
 */

/**
 * Which file opens by itself: the one that still needs something, else the last one that did something. Never a
 * withdrawn one -- nothing took effect on it.
 */
function defaultOpenSubmission(
  submissions: { submissionId?: string; status?: SubmissionStatus }[] | null | undefined,
): string | null {
  const rows = submissions ?? [];
  const pending = rows.find((s) => s.status === 'PENDING');
  const accepted = rows.find((s) => s.status === 'ACCEPTED');
  return pending?.submissionId ?? accepted?.submissionId ?? null;
}

type FileDirection = 'joining' | 'ending';

export function CreditLifeSchemePage() {
  const { policyNumber = '' } = useParams<{ policyNumber: string }>();
  // Back to the lender it was opened from, when it was (2026-10-20); otherwise its policy.
  const breadcrumb = useBreadcrumb([
    { label: 'Policies', to: '/staff/policies' },
    { label: policyNumber, to: `/staff/policies/${encodeURIComponent(policyNumber)}` },
  ]);
  const [params, setParams] = useSearchParamsKeepingTrail();
  const direction: FileDirection = params.get('file') === 'ending' ? 'ending' : 'joining';
  const [chosenEnrolment, setChosenEnrolment] = useState<string | null | undefined>(undefined);
  const [chosenExits, setChosenExits] = useState<string | null | undefined>(undefined);

  const scheme = usePolicyStore(selectScheme(policyNumber));
  const loadScheme = usePolicyStore((s) => s.loadScheme);
  const policyRecord = usePolicyStore(selectDetail(policyNumber));
  const loadDetail = usePolicyStore((s) => s.loadDetail);

  const enrolments = useCreditLifeStore(selectEnrolments(policyNumber));
  const exits = useCreditLifeStore(selectExits(policyNumber));
  const agreements = useCreditLifeStore(selectAgreements(policyNumber));
  const commission = useCommission(policyNumber);

  // DERIVED rather than stored: `undefined` means nobody has chosen and the default opens; `null` means somebody
  // collapsed it on purpose. That keeps the default without a setState inside a useEffect.
  const openEnrolment = chosenEnrolment !== undefined ? chosenEnrolment : defaultOpenSubmission(enrolments.data);
  const openExits = chosenExits !== undefined ? chosenExits : defaultOpenSubmission(exits.data);

  const enrolmentRows = useCreditLifeStore(selectEnrolmentRows(openEnrolment));
  const openQuotation = useCreditLifeStore(selectQuotation(openEnrolment ?? ''));
  const exitRows = useCreditLifeStore(selectExitRows(openExits));
  const enrolmentUpload = useCreditLifeStore(selectUploading(policyNumber, 'enrolment'));
  const exitsUpload = useCreditLifeStore(selectUploading(policyNumber, 'exits'));
  const decidingEnrolment = useCreditLifeStore(selectDeciding(openEnrolment));
  const decidingExits = useCreditLifeStore(selectDeciding(openExits));

  const store = useCreditLifeStore();

  useEffect(() => {
    if (!policyNumber) return;
    void loadScheme(policyNumber);
    // The policy record, for who set the scheme up and on what basis (policy V24).
    void loadDetail(policyNumber);
    void store.loadEnrolments(policyNumber);
    void store.loadExits(policyNumber);
    void store.loadAgreements(policyNumber);
    // The store actions are stable zustand references; re-running on them would refetch forever.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [policyNumber]);

  useEffect(() => {
    if (openEnrolment) {
      void store.loadEnrolmentRows(policyNumber, openEnrolment);
      void store.loadQuotation(policyNumber, openEnrolment);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [policyNumber, openEnrolment]);

  useEffect(() => {
    if (openExits) void store.loadExitRows(policyNumber, openExits);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [policyNumber, openExits]);

  const enrolmentSubmissions: SubmissionRow[] = useMemo(
    () =>
      (enrolments.data ?? []).map((s) => ({
        submissionId: s.submissionId ?? '',
        status: s.status ?? 'PENDING',
        fileName: s.fileName ?? NO_VALUE,
        rowCount: s.rowCount ?? 0,
        appliedCount: s.enrolledCount ?? 0,
        rejectedCount: s.rejectedCount ?? 0,
        submittedBy: s.submittedBy ?? NO_VALUE,
        submittedAt: s.submittedAt ?? '',
        acceptedBy: s.acceptedBy ?? null,
      })),
    [enrolments.data],
  );

  const exitSubmissions: SubmissionRow[] = useMemo(
    () =>
      (exits.data ?? []).map((s) => ({
        submissionId: s.submissionId ?? '',
        status: s.status ?? 'PENDING',
        fileName: s.fileName ?? NO_VALUE,
        rowCount: s.rowCount ?? 0,
        appliedCount: s.exitedCount ?? 0,
        rejectedCount: s.rejectedCount ?? 0,
        submittedBy: s.submittedBy ?? NO_VALUE,
        submittedAt: s.submittedAt ?? '',
        acceptedBy: s.acceptedBy ?? null,
      })),
    [exits.data],
  );

  if (isInitialLoad(scheme)) return <LoadingBlock label="Loading scheme" />;
  if (scheme.error && !scheme.data) {
    return (
      <>
        <PageHeader breadcrumb={breadcrumb} title={policyNumber} />
        <div className="px-6 pt-6">
          <ErrorPanel error={scheme.error} onRetry={() => void loadScheme(policyNumber)} />
        </div>
      </>
    );
  }

  const data = scheme.data;
  const currency = data?.totalCovered?.currencyCode ?? data?.fcl?.currencyCode ?? 'TZS';
  // Agreement first (2026-11-01): with no agreement there is no price, so no file and no template. Said only once the
  // agreements have loaded, so a slow read never shows a scheme as blocked that is not.
  const noAgreement = agreements.data != null && agreements.data.length === 0;
  const current = agreements.data ? agreementOn(agreements.data) : null;
  const agreedCommission = current?.commissionPercent ?? null;
  const openEnrolmentStatus = enrolments.data?.find((s) => s.submissionId === openEnrolment)?.status;

  function goTo(tab: string, file?: FileDirection) {
    setParams(
      (now) => {
        const next = new URLSearchParams(now);
        if (tab === 'files') next.delete('tab');
        else next.set('tab', tab);
        if (file === 'ending') next.set('file', 'ending');
        else if (file === 'joining') next.delete('file');
        return next;
      },
      { replace: true },
    );
  }

  const tabs: TabDef[] = [
    { value: 'files', label: 'Monthly files', content: <div className="space-y-6 pt-4">{renderFiles()}</div> },
    {
      value: 'agreement',
      label: 'Agreement',
      content: (
        <div className="pt-4">
          <Panel title="Yearly agreements">
            <CreditLifeAgreementsPanel policyNumber={policyNumber} currency={currency} />
          </Panel>
        </div>
      ),
    },
    {
      value: 'commission',
      label: 'Commission',
      content: (
        <div className="space-y-6 pt-4">
          <Panel title="Commission">
            <CommissionPanel facts={commission.facts} agreedCommissionPercent={agreedCommission} />
          </Panel>
          <CommissionEarnerPanel facts={commission.facts} onChanged={commission.refresh} />
        </div>
      ),
    },
    { value: 'scheme', label: 'Scheme details', content: <div className="pt-4">{renderDetails()}</div> },
  ];

  return (
    <>
      <PageHeader
        breadcrumb={breadcrumb}
        title={policyNumber}
        status={data?.status ? <StatusBadge kind="policy" value={data.status} /> : undefined}
        description={
          <span className="flex flex-wrap items-center gap-1.5">
            {data?.policyholderPartyId ? (
              <Link to={`/staff/parties/${data.policyholderPartyId}`} className="underline underline-offset-2">
                <PartyName partyId={data.policyholderPartyId} />
              </Link>
            ) : (
              <span className="text-subtle-foreground">{NO_VALUE}</span>
            )}
            <span aria-hidden>·</span>
            {policyRecord.data?.productId ? (
              <ProductName productId={policyRecord.data.productId} withCode={false} />
            ) : (
              'Credit life'
            )}
          </span>
        }
      />

      <DetailLayout>
        <div className="space-y-6">
          {/* THE FIGURES THE SCHEME EXISTS FOR, every one of them the server's. */}
          <dl className="hero-scope grid grid-cols-1 gap-x-8 gap-y-4 rounded-2xl bg-hero p-6 sm:grid-cols-3">
            <Field
              stacked
              label="Borrowers on cover"
              value={data?.activeMemberCount != null ? String(data.activeMemberCount) : NO_VALUE}
              note={
                (data?.membersRequiringEvidence ?? 0) > 0
                  ? `${data?.membersRequiringEvidence} above the free cover limit`
                  : 'Everyone within the free cover limit'
              }
            />
            <Field
              stacked
              label="Total covered"
              value={data?.totalCovered ? formatMoney(data.totalCovered) : NO_VALUE}
              note="Changes as borrowers join and leave"
            />
            <Field
              stacked
              label="Price this year"
              value={current ? `${trimZeros(current.premiumRatePercent)}% a year` : 'No agreement'}
              note={current ? `Until ${formatDate(current.validTo)}` : 'Add the yearly agreement'}
            />
          </dl>

          <Journey
            current={current}
            agreementsLoaded={agreements.data != null}
            hasAgreements={!noAgreement}
            joining={enrolmentSubmissions}
            ending={exitSubmissions}
            onGo={goTo}
          />

          <RecordTabs label="Scheme sections" tabs={tabs} />
        </div>
      </DetailLayout>
    </>
  );

  function renderFiles(): ReactNode {
    return (
      <>
        {/* One file direction at a time: the same pill pair as every two-way switch on the console. */}
        <div className="flex w-fit gap-1 rounded-xl bg-control p-1" role="group" aria-label="Which file">
          {(
            [
              ['joining', 'Borrowers joining', enrolmentSubmissions],
              ['ending', 'Loans ending', exitSubmissions],
            ] as const
          ).map(([value, label, rows]) => {
            const waiting = rows.some((s) => s.status === 'PENDING');
            return (
              <button
                key={value}
                type="button"
                aria-pressed={direction === value}
                onClick={() => goTo('files', value)}
                className="inline-flex min-h-8 items-center gap-2 rounded-lg px-3.5 text-sm font-medium text-muted-foreground transition-colors hover:text-foreground aria-pressed:bg-surface aria-pressed:text-foreground aria-pressed:shadow-raise pointer-coarse:min-h-11"
              >
                {label}
                {waiting && (
                  <span className="size-2 rounded-full bg-status-pending-fg">
                    <span className="sr-only">, a file waits for acceptance</span>
                  </span>
                )}
              </button>
            );
          })}
        </div>

        {direction === 'joining' ? (
          <SubmissionsPanel
            kind="enrolment"
            title="Joining files"
            appliedNoun="enrolled"
            submissions={enrolmentSubmissions}
            loading={isInitialLoad(enrolments)}
            error={enrolments.error}
            onRetry={() => void store.loadEnrolments(policyNumber)}
            selectedId={openEnrolment}
            onSelect={setChosenEnrolment}
            onUpload={(file) => void store.uploadEnrolment(policyNumber, file)}
            uploading={enrolmentUpload.status === 'loading'}
            uploadError={enrolmentUpload.error}
            // Acceptance creates cover, so the figures above are stale the instant it returns; the page owns them.
            onAccept={(id) => void store.acceptEnrolment(policyNumber, id).then(() => loadScheme(policyNumber))}
            onWithdraw={(id) => void store.withdrawEnrolment(policyNumber, id)}
            deciding={decidingEnrolment.status === 'loading'}
            decisionError={decidingEnrolment.status === 'error' ? decidingEnrolment.error : null}
            onDownloadReport={async (id) =>
              saveBlob(await downloadEnrolmentReport(policyNumber, id), enrolmentReportFileName(id))
            }
            onDownloadQuotation={async (id) =>
              saveBlob(await downloadEnrolmentQuotation(policyNumber, id), enrolmentQuotationFileName(policyNumber, id))
            }
            // The spreadsheet leads: Excel rewrites the dates in a CSV, and the row is refused.
            onDownloadTemplate={async () =>
              saveBlob(await downloadEnrolmentTemplateXlsx(policyNumber), enrolmentTemplateXlsxFileName(policyNumber))
            }
            onDownloadTemplateCsv={async () =>
              saveBlob(await downloadEnrolmentTemplate(policyNumber), enrolmentTemplateFileName(policyNumber))
            }
            columnGuide={<EnrolmentColumns />}
            blockedBy={noAgreement ? 'Add the lender’s yearly agreement first — every loan is priced on it.' : null}
            acceptConsequence={(id) => {
              const q = useCreditLifeStore.getState().quotations[id]?.data;
              if (!q) return null;
              const amount = (value: string) => formatMoney({ amount: value, currencyCode: q.currency });
              return (
                <>
                  <strong>
                    {q.borrowers} {q.borrowers === 1 ? 'borrower is' : 'borrowers are'} enrolled
                  </strong>{' '}
                  and the lender is invoiced <strong>{amount(q.amountToPay)}</strong>
                  {isPositive(q.commission) ? <>; their commission of {amount(q.commission)} is paid after it is received</> : null}
                  . Refused rows are not covered.
                </>
              );
            }}
          >
            {openEnrolment && openEnrolmentStatus && (
              <EnrolmentQuotation submissionId={openEnrolment} status={openEnrolmentStatus} />
            )}
            <EnrolmentRows
              decided={openEnrolmentStatus === 'ACCEPTED'}
              quotedLines={openQuotation.data?.lines ?? []}
              rows={enrolmentRows.data ?? []}
              loading={isInitialLoad(enrolmentRows)}
              error={enrolmentRows.error}
            />
          </SubmissionsPanel>
        ) : (
          <SubmissionsPanel
            kind="exits"
            title="Loans-ending files"
            appliedNoun="exited"
            submissions={exitSubmissions}
            loading={isInitialLoad(exits)}
            error={exits.error}
            onRetry={() => void store.loadExits(policyNumber)}
            selectedId={openExits}
            onSelect={setChosenExits}
            onUpload={(file) => void store.uploadExits(policyNumber, file)}
            uploading={exitsUpload.status === 'loading'}
            uploadError={exitsUpload.error}
            onAccept={(id) => void store.acceptExits(policyNumber, id).then(() => loadScheme(policyNumber))}
            onWithdraw={(id) => void store.withdrawExits(policyNumber, id)}
            deciding={decidingExits.status === 'loading'}
            decisionError={decidingExits.status === 'error' ? decidingExits.error : null}
            onDownloadReport={async (id) => saveBlob(await downloadExitsReport(policyNumber, id), exitsReportFileName(id))}
            onDownloadTemplate={async () =>
              saveBlob(await downloadExitsTemplateXlsx(policyNumber), exitsTemplateXlsxFileName(policyNumber))
            }
            onDownloadTemplateCsv={async () =>
              saveBlob(await downloadExitsTemplate(policyNumber), exitsTemplateFileName(policyNumber))
            }
            columnGuide={<ExitColumns />}
          >
            <ExitRows rows={exitRows.data ?? []} loading={isInitialLoad(exitRows)} error={exitRows.error} />
          </SubmissionsPanel>
        )}
      </>
    );
  }

  function renderDetails(): ReactNode {
    return (
      <Panel title="Scheme details">
        <dl className="px-4 pb-2">
          <Field label="Free cover limit" value={data?.fcl ? formatMoney(data.fcl) : 'No limit'}
            {...(data?.fcl ? { note: 'Above it a borrower is covered up to the limit.' } : {})} />
          <Field
            label="Above the free cover limit"
            value={data?.membersRequiringEvidence != null ? String(data.membersRequiringEvidence) : NO_VALUE}
          />
          <Field label="Commenced" value={formatDate(data?.commencementDate)} />
          {/* Set up by an underwriter from terms agreed with the lender -- no underwriting case -- so who agreed them
              is kept, with the basis and reason. */}
          <Field
            label="Set up by"
            value={policyRecord.data?.issuedByName ?? 'Not recorded'}
            note={
              [
                policyRecord.data?.issuanceBasis ? humanizeStatus(policyRecord.data.issuanceBasis) : null,
                policyRecord.data?.issuanceReason ?? null,
              ]
                .filter(Boolean)
                .join(' — ')
            }
          />
          {/* Schemes are not ceded (credit-life spec 0a): the insurer keeps every borrower's risk. */}
          <Field label="Reinsurance" value="Not reinsured" note="The insurer keeps all of this scheme’s risk." />
        </dl>
        <FreeCoverLimitEditor policyNumber={policyNumber} />
        <div className="flex flex-wrap gap-2 border-t border-border px-4 py-3">
          <Button asChild size="sm" variant="outline">
            <Link to={`/staff/group-schemes/${encodeURIComponent(policyNumber)}`}>Find a borrower</Link>
          </Button>
          <Button asChild size="sm" variant="ghost">
            <Link to={`/staff/policies/${encodeURIComponent(policyNumber)}`}>Invoices and the policy record</Link>
          </Button>
        </div>
      </Panel>
    );
  }
}

type StepState = 'done' | 'now' | 'wait' | 'later';

interface Step {
  title: string;
  detail: string;
  state: StepState;
  tab: string;
  file?: FileDirection;
}

/**
 * The scheme's month as three steps -- the agreement, borrowers joining, loans ending -- and the one thing to do next.
 * Each step opens where its work is done.
 */
function Journey({
  current,
  agreementsLoaded,
  hasAgreements,
  joining,
  ending,
  onGo,
}: {
  current: CreditLifeAgreement | null;
  agreementsLoaded: boolean;
  hasAgreements: boolean;
  joining: SubmissionRow[];
  ending: SubmissionRow[];
  onGo: (tab: string, file?: FileDirection) => void;
}) {
  const fileStep = (rows: SubmissionRow[], title: string, file: FileDirection, none: string): Step => {
    const pending = rows.find((s) => s.status === 'PENDING');
    const accepted = rows.find((s) => s.status === 'ACCEPTED');
    if (pending) return { title, detail: 'A file waits for acceptance', state: 'wait', tab: 'files', file };
    if (accepted) {
      return { title, detail: `Last accepted ${formatInstant(accepted.submittedAt)}`, state: 'done', tab: 'files', file };
    }
    return { title, detail: none, state: current ? 'now' : 'later', tab: 'files', file };
  };

  const steps: Step[] = [
    current
      ? {
          title: 'Yearly agreement',
          detail: `${pricingRuleInWords(current)}, to ${formatDate(current.validTo)}`,
          state: 'done',
          tab: 'agreement',
        }
      : {
          title: 'Yearly agreement',
          detail: !agreementsLoaded ? 'Loading…' : hasAgreements ? 'None covers today' : 'Not added yet',
          state: agreementsLoaded ? 'now' : 'later',
          tab: 'agreement',
        },
    fileStep(joining, 'Borrowers joining', 'joining', 'No file yet'),
    { ...fileStep(ending, 'Loans ending', 'ending', 'No loans ended yet'), ...(ending.length === 0 && current ? { state: 'later' as const } : {}) },
  ];

  const next: { text: string; label: string; tab: string; file?: FileDirection } | null = !agreementsLoaded
    ? null
    : !current
      ? { text: 'Add the lender’s yearly agreement. No file can be sent until it prices the loans.', label: 'Add the agreement', tab: 'agreement' }
      : joining.some((s) => s.status === 'PENDING')
        ? { text: 'A joining file is waiting for a second person to accept it.', label: 'Review the file', tab: 'files', file: 'joining' }
        : ending.some((s) => s.status === 'PENDING')
          ? { text: 'A loans-ending file is waiting for a second person to accept it.', label: 'Review the file', tab: 'files', file: 'ending' }
          : { text: 'Up to date. Upload the lender’s next file when it arrives.', label: 'Go to the files', tab: 'files', file: 'joining' };

  return (
    <Panel title="Next step">
      <div className="space-y-5 px-4 pb-4">
        {next && (
          <div className="flex flex-wrap items-center justify-between gap-3">
            <p className="text-sm">{next.text}</p>
            <Button size="sm" variant="primary" onClick={() => onGo(next.tab, next.file)}>
              {next.label}
            </Button>
          </div>
        )}
        <ol className="grid gap-3 sm:grid-cols-3" aria-label="How this scheme runs">
          {steps.map((step, i) => (
            <li key={step.title} className="min-w-0" aria-current={step.state === 'now' || step.state === 'wait' ? 'step' : undefined}>
              <button
                type="button"
                onClick={() => onGo(step.tab, step.file)}
                className="flex w-full items-start gap-3 rounded-xl border border-border p-3 text-left transition-colors hover:bg-hover"
              >
                <span
                  aria-hidden
                  className={cn(
                    'grid size-7 shrink-0 place-items-center rounded-full text-xs font-semibold',
                    step.state === 'done' && 'bg-status-success-bg text-status-success-fg',
                    step.state === 'now' && 'bg-accent text-accent-foreground',
                    step.state === 'wait' && 'bg-status-pending-bg text-status-pending-fg',
                    step.state === 'later' && 'bg-control text-muted-foreground',
                  )}
                >
                  {step.state === 'done' ? <Check className="size-4" strokeWidth={3} /> : i + 1}
                </span>
                <span className="min-w-0">
                  <span className="block text-sm font-medium">{step.title}</span>
                  <span
                    className={cn(
                      'mt-0.5 block text-xs',
                      step.state === 'wait' ? 'text-status-pending-fg' : 'text-muted-foreground',
                    )}
                  >
                    {step.detail}
                  </span>
                  <span className="sr-only">
                    {step.state === 'done' ? ' — done' : step.state === 'wait' ? ' — waiting' : step.state === 'now' ? ' — to do' : ''}
                  </span>
                </span>
              </button>
            </li>
          ))}
        </ol>
      </div>
    </Panel>
  );
}

/** What goes in the lender's joining spreadsheet, in the spreadsheet's own column names. */
function EnrolmentColumns() {
  return (
    <ColumnTable
      tip="Send the spreadsheet, not the CSV: Excel changes the dates in a CSV and the row is refused. Grey columns fill themselves, and the Check column says OK or what is wrong."
      columns={[
        ["Borrower's full name", true, 'As on the loan agreement'],
        ['Date of birth', true, 'Checked against the agreement’s ages'],
        ['Loan amount', true, 'What was lent; cover starts here and declines'],
        ['Loan term (months)', true, 'Whole months, within the agreement'],
        ['Loan start date', true, 'Cover starts on it; not in the future'],
        ['Sex', false, 'M or F'],
        ['Loan account no., National ID, Phone', false, 'Help find the borrower at a claim'],
      ]}
    />
  );
}

/** What goes in the loans-ending spreadsheet. */
function ExitColumns() {
  return (
    <ColumnTable
      tip="A restructure or top-up is an exit plus a new joining row. A death is not an exit — register a claim."
      columns={[
        ['Loan account no.', true, 'As on the file that insured it'],
        ['Date the loan ended', true, 'Cover ends; unused premium is refunded'],
        ['Why it ended', true, 'Settled early, Refinanced, Written off or Cancelled'],
        ['Balance still owed', false, 'If the lender keeps it'],
        ['Member reference', false, 'Only for a loan with no account number'],
      ]}
    />
  );
}

function ColumnTable({ tip, columns }: { tip: string; columns: [string, boolean, string][] }) {
  return (
    <div className="space-y-2">
      <table className="w-full text-sm">
        <caption className="sr-only">The columns of the lender’s file</caption>
        <thead>
          <tr className="text-left text-xs text-muted-foreground">
            <th scope="col" className="py-1.5 pr-4 font-medium">Column</th>
            <th scope="col" className="py-1.5 pr-4 font-medium">Needed</th>
            <th scope="col" className="py-1.5 font-medium">Notes</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-border">
          {columns.map(([name, required, note]) => (
            <tr key={name}>
              <td className="py-1.5 pr-4 font-medium whitespace-nowrap">{name}</td>
              <td className="py-1.5 pr-4 text-xs">
                {required ? 'Required' : <span className="text-muted-foreground">Optional</span>}
              </td>
              <td className="py-1.5 text-muted-foreground">{note}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="text-xs text-muted-foreground">{tip}</p>
    </div>
  );
}

/**
 * The rows of one joining file. Refused rows first: a refused row is a borrower with no insurance whose lender may
 * believe otherwise. The reference leads after the name because the lender quotes it on every later file.
 */
function EnrolmentRows({ rows, loading, error, decided, quotedLines }: {
  rows: EnrolmentRowView[];
  loading: boolean;
  error: ApiError | null;
  /** Only an accepted file enrolled anybody; until then a passing row reads as passing, not enrolled. */
  decided: boolean;
  /** The file's quotation, line by line: a row's premium before acceptance writes it (2026-11-02). */
  quotedLines: { lineNumber: number; premium: string }[];
}) {
  const quoted = useMemo(() => new Map(quotedLines.map((l) => [l.lineNumber, l.premium])), [quotedLines]);
  const ordered = useMemo(
    () => [...rows].sort((a, b) => Number(b.outcome === 'REJECTED') - Number(a.outcome === 'REJECTED')),
    [rows],
  );

  if (loading) return <TableSkeleton rows={4} columns={5} />;
  if (error) return <ErrorPanel error={error} />;
  if (ordered.length === 0) return <EmptyState title="No rows recorded for this file" />;

  // The loan, not the bookkeeping (2026-11-02): who, how much, from when, for how long, and what it costs. A refused
  // row says why under the name, in red; a row that passed needs no outcome column to say so.
  return (
    <div className="overflow-x-auto border-t border-border bg-surface">
      <table className="w-full text-sm">
        <caption className="sr-only">Every borrower in this file and their loan</caption>
        <thead>
          <tr className="border-b border-border bg-band text-left text-xs text-muted-foreground">
            <th scope="col" className="px-4 py-2 font-medium">Line</th>
            <th scope="col" className="px-4 py-2 font-medium">Borrower</th>
            <th scope="col" className="px-4 py-2 text-right font-medium">Loan amount</th>
            <th scope="col" className="px-4 py-2 font-medium">Loan start</th>
            <th scope="col" className="px-4 py-2 text-right font-medium">Term</th>
            <th scope="col" className="px-4 py-2 text-right font-medium">Premium</th>
            <th scope="col" className="px-4 py-2 font-medium">Reference</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-border">
          {ordered.map((r) => {
            const refused = r.outcome === 'REJECTED';
            return (
              <tr key={r.lineNumber} className={cn(refused && 'bg-status-danger-bg/40')}>
                <td className="px-4 py-2 align-top tabular-nums text-muted-foreground">{r.lineNumber}</td>
                <td className="px-4 py-2 align-top">
                  {r.borrowerFullName || NO_VALUE}
                  {refused && (
                    <span className="mt-0.5 block text-xs text-status-danger-fg">
                      Refused: {r.reason ?? r.reasonCode ?? 'not covered'}
                      <span className="sr-only"> — this borrower is not covered</span>
                    </span>
                  )}
                </td>
                <td className="px-4 py-2 align-top text-right tabular-nums whitespace-nowrap">
                  {r.loanAmount ? formatMoney(r.loanAmount) : NO_VALUE}
                </td>
                <td className="px-4 py-2 align-top tabular-nums whitespace-nowrap">{formatDate(r.loanStartDate)}</td>
                <td className="px-4 py-2 align-top text-right tabular-nums whitespace-nowrap">
                  {r.loanTermMonths != null ? `${r.loanTermMonths} mo` : NO_VALUE}
                </td>
                {/* Written at acceptance; before then the file's quotation prices each row the same way. */}
                <td className="px-4 py-2 align-top text-right tabular-nums whitespace-nowrap">
                  {r.premiumAmount ? formatMoney(r.premiumAmount)
                    : !refused && quoted.has(r.lineNumber) && r.loanAmount
                      ? formatMoney({ amount: quoted.get(r.lineNumber) ?? '0', currencyCode: r.loanAmount.currencyCode })
                      : <span className="text-subtle-foreground">{NO_VALUE}</span>}
                </td>
                {/* Minted at acceptance, so a pending row has none yet. */}
                <td className="px-4 py-2 align-top whitespace-nowrap font-mono text-xs">
                  {r.memberReference ?? (
                    <span className="font-sans text-subtle-foreground">
                      {refused ? NO_VALUE : decided ? NO_VALUE : 'on acceptance'}
                    </span>
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

/** The rows of one loans-ending file. Refused first, for the same reason. */
function ExitRows({ rows, loading, error }: { rows: ExitRowView[]; loading: boolean; error: ApiError | null }) {
  const ordered = useMemo(
    () => [...rows].sort((a, b) => Number(b.outcome === 'REJECTED') - Number(a.outcome === 'REJECTED')),
    [rows],
  );

  if (loading) return <TableSkeleton rows={4} columns={7} />;
  if (error) return <ErrorPanel error={error} />;
  if (ordered.length === 0) return <EmptyState title="No rows recorded for this file" />;

  return (
    <div className="overflow-x-auto border-t border-border bg-surface">
      <table className="w-full text-sm">
        <caption className="sr-only">Every row of this file and what the platform made of it</caption>
        <thead>
          <tr className="border-b border-border bg-band text-left text-xs text-muted-foreground">
            <th scope="col" className="px-4 py-2 font-medium">Line</th>
            <th scope="col" className="px-4 py-2 font-medium">Loan account</th>
            <th scope="col" className="px-4 py-2 font-medium">Reference</th>
            <th scope="col" className="px-4 py-2 font-medium">Ended on</th>
            <th scope="col" className="px-4 py-2 font-medium">Why it ended</th>
            <th scope="col" className="px-4 py-2 text-right font-medium">Balance still owed</th>
            <th scope="col" className="px-4 py-2 font-medium">Why refused</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-border">
          {ordered.map((r) => {
            const refused = r.outcome === 'REJECTED';
            return (
              <tr key={r.lineNumber} className={cn(refused && 'bg-status-danger-bg/30')}>
                <td className="px-4 py-2 tabular-nums text-muted-foreground">{r.lineNumber}</td>
                <td className="px-4 py-2 font-mono text-xs">{r.loanAccountNumber ?? NO_VALUE}</td>
                <td className="px-4 py-2 whitespace-nowrap font-mono text-xs">{r.memberReference ?? NO_VALUE}</td>
                <td className="px-4 py-2 tabular-nums">{formatDate(r.exitDate)}</td>
                <td className="px-4 py-2">{r.exitReason ? humanizeStatus(r.exitReason) : NO_VALUE}</td>
                <td className="px-4 py-2 text-right tabular-nums">
                  {r.outstandingBalanceAtExit ? formatMoney(r.outstandingBalanceAtExit) : NO_VALUE}
                </td>
                <td className="px-4 py-2 text-xs">
                  {refused ? (
                    <span className="text-status-danger-fg">{r.reason ?? r.reasonCode ?? 'Refused'}</span>
                  ) : (
                    <span className="text-muted-foreground">{NO_VALUE}</span>
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
