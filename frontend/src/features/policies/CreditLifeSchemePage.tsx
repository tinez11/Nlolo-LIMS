import { ArrowLeft } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { enrolmentReportPath, exitsReportPath } from '@/api/creditLife';
import type { EnrolmentRowView, ExitRowView } from '@/api/types';
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
import { selectScheme, usePolicyStore } from '@/store/policyStore';
import { SubmissionsPanel, type SubmissionRow } from './SubmissionsPanel';

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
export function CreditLifeSchemePage() {
  const { policyNumber = '' } = useParams<{ policyNumber: string }>();
  const [chosenEnrolment, setChosenEnrolment] = useState<string | null | undefined>(undefined);
  const [chosenExits, setChosenExits] = useState<string | null | undefined>(undefined);

  const scheme = usePolicyStore(selectScheme(policyNumber));
  const loadScheme = usePolicyStore((s) => s.loadScheme);

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
  const newestEnrolmentId = enrolments.data?.[0]?.submissionId ?? null;
  const newestExitId = exits.data?.[0]?.submissionId ?? null;
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
    void store.loadEnrolments(policyNumber);
    void store.loadExits(policyNumber);
    // The store actions are stable zustand references; re-running on them would refetch forever.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [policyNumber]);

  useEffect(() => {
    if (openEnrolment) void store.loadEnrolmentRows(policyNumber, openEnrolment);
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

  return (
    <>
      <PageHeader
        title={
          <span className="flex flex-wrap items-center gap-2">
            {policyNumber}
            <StatusBadge kind="policy" value={data?.status} />
          </span>
        }
        description={
          <span className="flex flex-wrap items-center gap-1.5">
            Credit life ·
            {data?.policyholderPartyId ? (
              <PartyName partyId={data.policyholderPartyId} />
            ) : (
              <span className="text-subtle-foreground">{NO_VALUE}</span>
            )}
          </span>
        }
        actions={
          <Link
            to={`/staff/policies/${encodeURIComponent(policyNumber)}`}
            className="inline-flex items-center gap-1.5 text-sm underline underline-offset-2"
          >
            <ArrowLeft className="size-4" aria-hidden />
            Policy record
          </Link>
        }
      />

      <DetailLayout
        record={
          <>
            {/* The terms a person needs in view while judging a file, and nothing that belongs
                to a different benefit basis. No grades, no salary multiple: this product has
                neither, and an empty grade table would be furniture. */}
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
                        note: 'A borrower above this is covered up to it; the excess is the lender\u2019s credit risk.',
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
              </dl>
              {/* The premium rate and interest method this scheme was issued with are NOT here,
                  and that is the platform's limit rather than an editorial choice: GroupSchemeView
                  carries neither, so the console cannot show them without inventing them. Recorded
                  as a gap rather than filled with a plausible number. */}
              <div className="border-t border-border px-4 py-3">
                <Link
                  to={`/staff/group-schemes/${encodeURIComponent(policyNumber)}`}
                  className="text-sm underline underline-offset-2"
                >
                  The member roll
                </Link>
                <p className="mt-1 text-xs text-muted-foreground">
                  Searchable by borrower name \u2014 the only way to answer \u201cis this person covered\u201d on
                  a roll of hundreds.
                </p>
              </div>
            </Panel>
          </>
        }
      >
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
          onAccept={(id) => void store.acceptEnrolment(policyNumber, id)}
          onWithdraw={(id) => void store.withdrawEnrolment(policyNumber, id)}
          deciding={decidingEnrolment.status === 'loading'}
          reportHref={(id) => enrolmentReportPath(policyNumber, id)}
        >
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
          onAccept={(id) => void store.acceptExits(policyNumber, id)}
          onWithdraw={(id) => void store.withdrawExits(policyNumber, id)}
          deciding={decidingExits.status === 'loading'}
          reportHref={(id) => exitsReportPath(policyNumber, id)}
        >
          <ExitRows rows={exitRows.data ?? []} loading={isInitialLoad(exitRows)} error={exitRows.error} />
        </SubmissionsPanel>
      </DetailLayout>
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
      {/* The reference the insurer minted is NOT on these rows -- EnrolmentRowView carries the
          lender's own loan account number and nothing else identifying. It exists only in the
          report's first column, which is why that download is the deliverable rather than a
          convenience, and why this says so instead of leaving a person to wonder. */}
      <p className="px-4 py-2 text-xs text-muted-foreground">
        Member references are in the report, not here \u2014 it is the only place the lender learns
        them.
      </p>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <caption className="sr-only">
            Every row of this schedule and what the platform made of it
          </caption>
          <thead>
            <tr className="border-y border-border text-left text-xs text-muted-foreground">
              <th scope="col" className="px-4 py-2 font-medium">Line</th>
              <th scope="col" className="px-4 py-2 font-medium">Borrower</th>
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
                  <td className="px-4 py-2 font-mono text-xs">{r.loanAccountNumber ?? NO_VALUE}</td>
                  <td className="px-4 py-2">
                    <StatusBadge kind="enrolmentOutcome" value={r.outcome} />
                  </td>
                  <td className="px-4 py-2 text-xs">
                    {refused ? (
                      <span className="text-status-danger-fg">
                        {r.reason ?? r.reasonCode ?? 'Refused'}
                        <span className="sr-only"> \u2014 this borrower is not covered</span>
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
