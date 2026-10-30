import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { useBreadcrumb } from '@/lib/cameFrom';
import { Link, useParams } from 'react-router-dom';
import {
  downloadEnrolmentReport,
  downloadEnrolmentTemplate,
  downloadEnrolmentTemplateXlsx,
  downloadExitsReport,
  downloadExitsTemplate,
  downloadExitsTemplateXlsx,
  enrolmentReportFileName,
  enrolmentTemplateFileName,
  enrolmentTemplateXlsxFileName,
  exitsReportFileName,
  exitsTemplateFileName,
  exitsTemplateXlsxFileName,
} from '@/api/creditLife';
import type { EnrolmentRowView, ExitRowView, SubmissionStatus } from '@/api/types';
import { DetailLayout } from '@/components/DetailLayout';
import { Field } from '@/components/Field';
import { PageHeader } from '@/components/PageHeader';
import { Panel } from '@/components/Panel';
import { PartyName } from '@/components/PartyName';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, LoadingBlock, TableSkeleton } from '@/components/states';
import type { ApiError } from '@/lib/apiError';
import { cn } from '@/lib/cn';
import { formatDate } from '@/lib/dates';
import { humanizeStatus } from '@/lib/status';
import { saveBlob } from '@/lib/download';
import { NO_VALUE, formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import {
  selectDeciding,
  selectEnrolmentRows,
  selectEnrolments,
  selectExitRows,
  selectExits,
  selectUploading,
  useCreditLifeStore,
} from '@/store/creditLifeStore';
import { selectDetail, selectScheme, usePolicyStore } from '@/store/policyStore';
import { ProductName } from '@/components/ProductName';
import { CommissionPanel } from './CommissionPanel';
import { AgreementInForce, CreditLifeAgreementsPanel } from './CreditLifeAgreementsPanel';
import { EnrolmentQuotation } from './EnrolmentQuotation';
import { isPositive } from './creditLifeAgreementForm';
import { FreeCoverLimitEditor } from './FreeCoverLimitEditor';
import { SubmissionsPanel, type SubmissionRow } from './SubmissionsPanel';
import { Tip } from '@/components/ui/tooltip';

/**
 * A credit-life scheme: a lender's book of borrowers, run one monthly file at a time.
 *
 * **Its own page rather than a branch inside `GroupSchemePage`**, because that page is built
 * around a benefit basis this product does not have. An employer scheme asks what each staff
 * grade is worth, or what multiple of salary; a credit-life scheme asks what each borrower still
 * owes, and derives the answer from an amortisation schedule nobody types in. Rendering the grade
 * table as an empty state on every credit-life scheme would have been the cheap version.
 *
 * **What the composition argues.** Two files run this product — borrowers joining, loans
 * ending — and both are propose-then-accept. So the work column is those two panels and nothing
 * else, in the order a month actually runs, with the terms pinned in the record rail beside them.
 * The member roll is reached from here rather than reproduced: a roll of several hundred is its
 * own screen, and the question this page answers is "what happened to the book this month",
 * not "who is on it".
 *
 * The one thing every element is arranged to prevent is reading an uploaded file as a finished
 * one. A submission that has not been accepted has enrolled nobody and exited nobody, and a
 * borrower a lender believes is covered but is not is the most expensive mistake available here.
 */
/**
 * Which file opens by itself: the one that still needs something, or the last one that did
 * something. Never a withdrawn one.
 *
 * <p>It used to be simply the newest, which is wrong in the case that actually happens. A lender
 * fighting a file format leaves a stack of withdrawn submissions on top, and a WITHDRAWN file is
 * by definition the one where nothing took effect — so the page would open on a table of rejected
 * rows that changed nothing, with the accepted file that DID change the book collapsed beneath it.
 *
 * <p>PENDING first because it is owed a decision and blocks the next upload; then the newest
 * ACCEPTED, which is what "what happened to this book" means. If every file was withdrawn,
 * nothing opens, which is honest: nothing has happened.
 *
 * <p>Withdrawn files stay in the list and stay clickable. See the panel for why they are kept.
 */
function defaultOpenSubmission(
  submissions: { submissionId?: string; status?: SubmissionStatus }[] | null | undefined,
): string | null {
  const rows = submissions ?? [];
  const pending = rows.find((s) => s.status === 'PENDING');
  const accepted = rows.find((s) => s.status === 'ACCEPTED');
  return pending?.submissionId ?? accepted?.submissionId ?? null;
}

export function CreditLifeSchemePage() {
  const { policyNumber = '' } = useParams<{ policyNumber: string }>();
  // Back to the lender it was opened from, when it was (2026-10-20); otherwise its policy.
  const breadcrumb = useBreadcrumb([
    { label: 'Policies', to: '/staff/policies' },
    { label: policyNumber, to: `/staff/policies/${encodeURIComponent(policyNumber)}` },
  ]);
  const [chosenEnrolment, setChosenEnrolment] = useState<string | null | undefined>(undefined);
  const [chosenExits, setChosenExits] = useState<string | null | undefined>(undefined);

  const scheme = usePolicyStore(selectScheme(policyNumber));
  const loadScheme = usePolicyStore((s) => s.loadScheme);
  const policyRecord = usePolicyStore(selectDetail(policyNumber));
  const loadDetail = usePolicyStore((s) => s.loadDetail);

  const enrolments = useCreditLifeStore(selectEnrolments(policyNumber));
  const exits = useCreditLifeStore(selectExits(policyNumber));

  /**
   * Which submission is open, DERIVED rather than stored.
   *
   * `undefined` means nobody has chosen yet and the newest file opens by itself; `null` means
   * somebody collapsed it on purpose. The distinction is what lets the default exist without a
   * `setState` inside a `useEffect`, which this console's lint bans outright — and the rule is
   * right: a stored copy of "the newest one" goes stale the moment a file is uploaded.
   *
   * The newest opens because somebody arriving here came to find out what happened to the last
   * file they sent, and the most important thing on it — WHY rows were refused — was previously
   * a click away behind a count.
   */
  const newestEnrolmentId = defaultOpenSubmission(enrolments.data);
  const newestExitId = defaultOpenSubmission(exits.data);
  const openEnrolment = chosenEnrolment !== undefined ? chosenEnrolment : newestEnrolmentId;
  const openExits = chosenExits !== undefined ? chosenExits : newestExitId;

  const enrolmentRows = useCreditLifeStore(selectEnrolmentRows(openEnrolment));
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
    return <ErrorPanel error={scheme.error} onRetry={() => void loadScheme(policyNumber)} />;
  }

  const data = scheme.data;
  const currency = data?.totalCovered?.currencyCode ?? data?.fcl?.currencyCode ?? 'TZS';
  const openEnrolmentStatus = enrolments.data?.find((s) => s.submissionId === openEnrolment)?.status;

  return (
    <>
      <PageHeader
        /*
          Two crumbs, because a scheme genuinely sits under a contract: Policies, then the
          policy this scheme belongs to. What this replaces was a "Policy record" link with a
          back arrow parked in `actions` -- navigation dressed as a control, and in the slot
          reserved for the things a person can DO to the record. The relationship it was
          expressing is exactly what a breadcrumb is for, so it moves rather than disappearing.
        */
        breadcrumb={breadcrumb}
        title={
          <span className="flex flex-wrap items-center gap-2">
            {policyNumber}
            <StatusBadge kind="policy" value={data?.status} />
          </span>
        }
        description={
          <span className="flex flex-wrap items-center gap-1.5">
            {policyRecord.data?.productId
              ? <ProductName productId={policyRecord.data.productId} withCode={false} />
              : 'Credit life'}{' '}·
            {data?.policyholderPartyId ? (
              <PartyName partyId={data.policyholderPartyId} />
            ) : (
              <span className="text-subtle-foreground">{NO_VALUE}</span>
            )}
          </span>
        }
      />

      <DetailLayout
        record={
          <>
            {/* The terms a person needs in view while judging a file, and nothing that belongs
                to a different benefit basis. No grades, no salary multiple: this product has
                neither, and an empty grade table would be furniture. */}
            {/* The year in force, beside the files it prices. Facts only: the year is added in the work column. */}
            <Panel title="Agreement" subtitle="With the lender, in force today">
              <AgreementInForce policyNumber={policyNumber} currency={currency} />
            </Panel>
            <Panel title="Terms" subtitle="What this scheme covers">
              {/* px-4 pb-2, the same as every other Field list on this platform. This had no
                  padding and a divide-y: the rows ran edge to edge into the panel border, and the
                  divider doubled the rule Field already draws for itself. */}
              <dl className="px-4 pb-2">
                <Field
                  label="Free cover limit"
                  value={data?.fcl ? formatMoney(data.fcl) : 'No limit'}
                  {...(data?.fcl
                    ? {
                        note: 'A borrower above this is covered up to it; the excess is the lender’s credit risk.',
                      }
                    : {})}
                />
                <Field
                  label="Lives on cover"
                  value={data?.activeMemberCount != null ? String(data.activeMemberCount) : NO_VALUE}
                  note="Borrowers currently insured."
                />
                <Field
                  label="Total covered"
                  value={data?.totalCovered ? formatMoney(data.totalCovered) : NO_VALUE}
                  note="Restated whenever a borrower joins or leaves."
                />
                <Field
                  label="Above the free cover limit"
                  value={
                    data?.membersRequiringEvidence != null
                      ? String(data.membersRequiringEvidence)
                      : NO_VALUE
                  }
                  note="Covered to the limit; the excess needs evidence nobody has supplied."
                />
                <Field label="Commenced" value={formatDate(data?.commencementDate)} />
                {/* A credit-life scheme is set up by an underwriter from terms agreed with the
                    lender -- there is no underwriting case -- so the record of who agreed them is
                    the underwriter's own name, kept for compliance with the basis and reason. */}
                <Field
                  label="Set up by"
                  value={policyRecord.data?.issuedByName ?? 'Not recorded'}
                  note={
                    [
                      policyRecord.data?.issuanceBasis ? humanizeStatus(policyRecord.data.issuanceBasis) : null,
                      policyRecord.data?.issuanceReason ?? null,
                    ]
                      .filter(Boolean)
                      .join(' — ') || 'Set up before the underwriter of record was kept.'
                  }
                />
                {/* Said, not assumed. Schemes are not ceded (credit-life spec 0a): the treaty
                    model cannot express a scheme treaty -- classes of business, per-scheme
                    limits, a cession that follows a declining balance -- so the insurer keeps
                    every borrower's risk, up to the free cover limit each. Deferred to a
                    reinsurance build of its own. */}
                <Field
                  label="Reinsurance"
                  value="Not reinsured"
                  note="The insurer retains 100% of this scheme's risk. Schemes are not ceded until the treaty model can express a scheme treaty."
                />
              </dl>
              {/* The premium rate and interest method this scheme was issued with are NOT here,
                  and that is the platform's limit rather than an editorial choice: GroupSchemeView
                  carries neither, so the console cannot show them without inventing them. Recorded
                  as a gap rather than filled with a plausible number. */}
              <FreeCoverLimitEditor policyNumber={policyNumber} />
              <div className="border-t border-border px-4 py-3">
                <Link
                  to={`/staff/group-schemes/${encodeURIComponent(policyNumber)}`}
                  className="text-sm underline underline-offset-2"
                >
                  The member roll
                </Link>
                <p className="mt-1 text-xs text-muted-foreground">
                  Searchable by borrower name — the only way to answer “is this person covered” on
                  a roll of hundreds.
                </p>
              </div>
            </Panel>
            <Panel title="Commission" subtitle="Who earns on each file, at what rate">
              <CommissionPanel policyNumber={policyNumber} />
            </Panel>
          </>
        }
      >
        <Panel
          title="Yearly agreement"
          subtitle="The rate, the limits and the lender's commission, agreed once a year. Each loan is priced on the year it started in."
        >
          <CreditLifeAgreementsPanel policyNumber={policyNumber} currency={currency} />
        </Panel>

        <SubmissionsPanel
          kind="enrolment"
          title="Borrowers joining"
          subtitle="The lender’s monthly schedule. Enrols nobody until a second person accepts it."
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
          // Acceptance is the act that creates cover, so the record rail beside it -- lives on
          // cover, total covered, how many sit above the free cover limit -- is stale the instant
          // it returns. The store reloads the submissions it owns and says in as many words that
          // the page owns the scheme; this is the page doing it. A withdrawal needs no reload,
          // because a withdrawn file changes nothing by definition.
          onAccept={(id) =>
            void store.acceptEnrolment(policyNumber, id).then(() => loadScheme(policyNumber))
          }
          onWithdraw={(id) => void store.withdrawEnrolment(policyNumber, id)}
          deciding={decidingEnrolment.status === 'loading'}
          onDownloadReport={async (id) =>
            saveBlob(await downloadEnrolmentReport(policyNumber, id), enrolmentReportFileName(id))
          }
          // The SPREADSHEET leads. A CSV template cannot survive Excel: two real files came back
          // with every date rewritten, including the worked example that was there to show the
          // format. A date in a spreadsheet is a typed cell, so Excel round-trips it.
          onDownloadTemplate={async () =>
            saveBlob(
              await downloadEnrolmentTemplateXlsx(policyNumber),
              enrolmentTemplateXlsxFileName(policyNumber),
            )
          }
          onDownloadTemplateCsv={async () =>
            saveBlob(
              await downloadEnrolmentTemplate(policyNumber),
              enrolmentTemplateFileName(policyNumber),
            )
          }
          columnGuide={<EnrolmentColumns />}
          // The confirmation states what accepting does in this file's own figures, from its quotation.
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
                . Refused rows are not covered, and the lender learns which from the report.
              </>
            );
          }}
        >
          {openEnrolment && openEnrolmentStatus && (
            <EnrolmentQuotation submissionId={openEnrolment} status={openEnrolmentStatus} />
          )}
          <EnrolmentRows
            rows={enrolmentRows.data ?? []}
            loading={isInitialLoad(enrolmentRows)}
            error={enrolmentRows.error}
          />
        </SubmissionsPanel>

        <SubmissionsPanel
          kind="exits"
          title="Loans ending"
          subtitle="Repaid, refinanced, written off or cancelled. Takes nobody off cover until a second person accepts it."
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
          // Same as the enrolment panel above: accepting exits takes borrowers OFF cover, so the
          // counts in the record rail no longer describe the scheme until the page refetches it.
          onAccept={(id) =>
            void store.acceptExits(policyNumber, id).then(() => loadScheme(policyNumber))
          }
          onWithdraw={(id) => void store.withdrawExits(policyNumber, id)}
          deciding={decidingExits.status === 'loading'}
          onDownloadReport={async (id) =>
            saveBlob(await downloadExitsReport(policyNumber, id), exitsReportFileName(id))
          }
          onDownloadTemplate={async () =>
            saveBlob(
              await downloadExitsTemplateXlsx(policyNumber),
              exitsTemplateXlsxFileName(policyNumber),
            )
          }
          onDownloadTemplateCsv={async () =>
            saveBlob(await downloadExitsTemplate(policyNumber), exitsTemplateFileName(policyNumber))
          }
          columnGuide={<ExitColumns />}
        >
          <ExitRows rows={exitRows.data ?? []} loading={isInitialLoad(exitRows)} error={exitRows.error} />
        </SubmissionsPanel>
      </DetailLayout>
    </>
  );
}

/**
 * What goes in the lender's spreadsheet, in the words somebody repeats to a lender (2026-10-30).
 *
 * <p>The spreadsheet is built from the scheme's agreement: its price, its limits and a Check column that names what
 * is wrong with a row as it is typed. These are its own column names, so the person explaining it and the lender
 * filling it read the same words. The CSV carries the same columns under their system names.
 *
 * <p>Kept short of restating the whole judgement: every rule that refuses a row is applied by the server and comes
 * back as a reason on that row.
 */
function EnrolmentColumns() {
  return (
    <dl className="grid gap-x-4 gap-y-1.5 text-xs sm:grid-cols-[auto_1fr]">
      <p className="sm:col-span-2 mb-1 text-xs text-muted-foreground">
        The spreadsheet carries this scheme&rsquo;s agreement: the price in words, totals at the top, and grey columns
        that fill themselves &mdash; the loan&rsquo;s end date, its premium and a Check that says OK or what is wrong.
        A row whose Check is not OK is refused when the file is sent back.
      </p>
      <p className="sm:col-span-2 mb-1 text-xs text-status-warning-fg">
        Send the spreadsheet, not the CSV, to anyone who works in Excel. Excel rewrites dates in a CSV and the row is
        refused; in a spreadsheet a date stays a date.
      </p>
      <Column name="Borrower's full name" required>
        As on the loan agreement. No client record is created for them.
      </Column>
      <Column name="Date of birth" required>
        Typed as Excel takes a date. The agreement&rsquo;s oldest borrower is checked against it.
      </Column>
      <Column name="Loan amount" required>
        What was lent. The premium is charged on this, and cover starts here and declines.
      </Column>
      <Column name="Loan term (months)" required>
        Whole months, within the agreement&rsquo;s shortest and longest loan.
      </Column>
      <Column name="Loan start date" required>
        The day the money was paid out. <strong>Cover starts on it</strong>, so it cannot be in the future.
      </Column>
      <Column name="Sex" required={false}>
        M or F. Not priced on; kept for the regulatory return.
      </Column>
      <Column name="Loan account no., National ID, Phone" required={false}>
        Help identify the borrower if a claim is ever made.
      </Column>
      <p className="sm:col-span-2 mt-1 text-xs text-muted-foreground">
        A lender&rsquo;s own spreadsheet is read too: its header may sit below a title, behind a Summary sheet, and in
        its own words (CLIENT NAME, SUM ASSURED, POLICY TERM, START DATE); TOTAL rows and extra columns are ignored.
        A row repeating a loan already on cover is refused rather than insuring it twice.
      </p>
    </dl>
  );
}

/** The exits file. Three required columns and a balance the lender may not track. */
function ExitColumns() {
  return (
    <dl className="grid gap-x-4 gap-y-1.5 text-xs sm:grid-cols-[auto_1fr]">
      {/* The opposite decision to the enrolment template's, and the reason is in the copy: an
          exits row that names a real member really would take them off cover. */}
      <p className="sm:col-span-2 mb-1 text-xs text-muted-foreground">
        The template&rsquo;s example row quotes a reference ending 000000, which belongs to nobody
        — references start at 000001. Returning it unchanged is refused rather than taking a
        borrower off cover, which is why this example names no real member.
      </p>
      <Column name="member_reference" required>
        {/* Required here and optional on an enrolment file, and the asymmetry is the point. */}
        The reference the insurer minted, from the enrolment report. An exits file is about a loan
        already on cover, so there is no new-borrower case here.
      </Column>
      <Column name="exit_date" required>
        YYYY-MM-DD. Cover ends on it, and an early settlement refunds premium from it.
      </Column>
      <Column name="exit_reason" required>
        SETTLED_EARLY, REFINANCED, WRITTEN_OFF or CANCELLED. A death is not one of these — a claim
        takes the borrower off cover, and a lender stating it here would suppress the refund.
      </Column>
      <Column name="outstanding_balance_at_exit" required={false}>
        What was still owed, if the lender tracks it.
      </Column>
      <p className="sm:col-span-2 mt-1 text-xs text-muted-foreground">
        A restructure or top-up is an exit and a fresh enrolment, never an amendment: the new loan
        is a different risk over a different term, and it earns its own reference.
      </p>
    </dl>
  );
}

function Column({
  name,
  required = false,
  children,
}: {
  name: string;
  required?: boolean;
  children: ReactNode;
}) {
  return (
    <>
      <dt className="font-mono text-xs whitespace-nowrap">
        {name}
        {required ? (
          <Tip content="Required">
            <span className="ml-1 text-status-danger-fg">
              <span aria-hidden>*</span>
              <span className="sr-only">required</span>
            </span>
          </Tip>
        ) : (
          <Tip content="Optional">
            <span className="ml-1 text-subtle-foreground">
              <span aria-hidden>·</span>
              <span className="sr-only">optional</span>
            </span>
          </Tip>
        )}
      </dt>
      <dd className="text-muted-foreground">{children}</dd>
    </>
  );
}

/**
 * The rows of one enrolment file.
 *
 * **Refused rows come first**, and that ordering is the panel's argument rather than a
 * convenience: a refused row is a borrower with no insurance whose lender may believe otherwise,
 * and it is the output of an upload rather than an exception to it. `member_reference` sits in
 * the first column because it is the only place the lender ever learns the reference the insurer
 * minted, and they quote it back on every later file.
 */
function EnrolmentRows({
  rows,
  loading,
  error,
}: {
  rows: EnrolmentRowView[];
  loading: boolean;
  error: ApiError | null;
}) {
  const ordered = useMemo(
    () =>
      [...rows].sort(
        (a, b) => Number(b.outcome === 'REJECTED') - Number(a.outcome === 'REJECTED'),
      ),
    [rows],
  );

  if (loading) return <TableSkeleton rows={4} columns={5} />;
  if (error) return <ErrorPanel error={error} />;
  if (ordered.length === 0) return <EmptyState title="No rows recorded for this file" />;

  return (
    <div className="border-t border-border bg-surface">
      {/* This said "member references are in the report, not here", which was true of the
          CONTRACT and false of the data: EnrolmentRowView has carried memberReference since
          enrolment was built, and the OpenAPI schema simply never declared it, so the generated
          client had no such field to read. The sentence then outlived the limitation it
          described. The reference is the deliverable, so where it can be shown it is. */}
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <caption className="sr-only">
            Every row of this schedule and what the platform made of it
          </caption>
          <thead>
            <tr className="border-y border-border text-left text-xs text-muted-foreground">
              <th scope="col" className="px-4 py-2 font-medium">Line</th>
              <th scope="col" className="px-4 py-2 font-medium">Borrower</th>
              <th scope="col" className="px-4 py-2 font-medium">Reference</th>
              <th scope="col" className="px-4 py-2 font-medium">Loan account</th>
              <th scope="col" className="px-4 py-2 font-medium">Outcome</th>
              <th scope="col" className="px-4 py-2 font-medium">Why</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {ordered.map((r) => {
              const refused = r.outcome === 'REJECTED';
              return (
                <tr key={r.lineNumber} className={cn(refused && 'bg-status-danger-bg/40')}>
                  <td className="px-4 py-2 tabular-nums text-muted-foreground">{r.lineNumber}</td>
                  <td className="px-4 py-2">{r.borrowerFullName ?? NO_VALUE}</td>
                  {/* Minted at ACCEPTANCE, so a pending or refused row has none yet -- and saying
                      "on acceptance" is more use than an em dash, which reads as missing. */}
                  <td className="px-4 py-2 whitespace-nowrap font-mono text-xs">
                    {r.memberReference ?? (
                      <span className="font-sans text-subtle-foreground">
                        {refused ? NO_VALUE : 'on acceptance'}
                      </span>
                    )}
                  </td>
                  <td className="px-4 py-2 font-mono text-xs">{r.loanAccountNumber ?? NO_VALUE}</td>
                  <td className="px-4 py-2">
                    <StatusBadge kind="enrolmentOutcome" value={r.outcome} />
                  </td>
                  <td className="px-4 py-2 text-xs">
                    {refused ? (
                      <span className="text-status-danger-fg">
                        {r.reason ?? r.reasonCode ?? 'Refused'}
                        <span className="sr-only"> — this borrower is not covered</span>
                      </span>
                    ) : (
                      <span className="text-subtle-foreground">{NO_VALUE}</span>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}

/** The rows of one exits file. Same ordering argument: refused first. */
function ExitRows({
  rows,
  loading,
  error,
}: {
  rows: ExitRowView[];
  loading: boolean;
  error: ApiError | null;
}) {
  const ordered = useMemo(
    () => [...rows].sort((a, b) => Number(b.outcome === 'REJECTED') - Number(a.outcome === 'REJECTED')),
    [rows],
  );

  if (loading) return <TableSkeleton rows={4} columns={5} />;
  if (error) return <ErrorPanel error={error} />;
  if (ordered.length === 0) return <EmptyState title="No rows recorded for this file" />;

  return (
    <div className="overflow-x-auto border-t border-border bg-surface">
      <table className="w-full text-sm">
        <caption className="sr-only">Every row of this exits file and what the platform made of it</caption>
        <thead>
          <tr className="border-b border-border text-left text-xs text-muted-foreground">
            <th scope="col" className="px-4 py-2 font-medium">Member reference</th>
            <th scope="col" className="px-4 py-2 font-medium">Left on</th>
            <th scope="col" className="px-4 py-2 font-medium">Reason</th>
            <th scope="col" className="px-4 py-2 font-medium">Outstanding</th>
            <th scope="col" className="px-4 py-2 font-medium">Why refused</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-border">
          {ordered.map((r) => {
            const refused = r.outcome === 'REJECTED';
            return (
              <tr key={r.lineNumber} className={cn(refused && 'bg-status-danger-bg/30')}>
                <td className="px-4 py-2 font-mono text-xs">{r.memberReference ?? NO_VALUE}</td>
                <td className="px-4 py-2 tabular-nums">{formatDate(r.exitDate)}</td>
                <td className="px-4 py-2">{r.exitReason ?? NO_VALUE}</td>
                <td className="px-4 py-2 tabular-nums">
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
