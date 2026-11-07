import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useAuth } from 'react-oidc-context';
import { Link } from 'react-router-dom';
import { getLoanHistory, getLoanLimit } from '@/api/policies';
import type { LoanHistoryLine, LoanLimit, LoanView } from '@/api/types';
import { can } from '@/auth/access';
import { readIdentity } from '@/auth/claims';
import { ConfirmAct } from '@/components/ConfirmAct';
import { DataTable, type Column } from '@/components/DataTable';
import { Field } from '@/components/Field';
import { StaffName } from '@/components/StaffName';
import { StatusBadge } from '@/components/StatusBadge';
import { barredAsOwn } from '@/lib/ownWork';
import { InlineError } from '@/components/InlineError';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { formatInstantDay } from '@/lib/dates';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import {
  selectLoans,
  selectOriginatingLoan,
  usePolicyStore,
} from '@/store/policyStore';
import {
  blankOriginateLoanForm,
  originateLoanFormSchema,
  toApiRequest as toOriginateApiRequest,
  type OriginateLoanFormValues,
} from './originateLoanForm';
import { FormField } from '@/components/FormField';
import { Input } from '@/components/ui/input';
import { VALIDATE_ON_TOUCH } from '@/lib/formTiming';

/** Only these two statuses accept a repayment -- `PolicyLoanApiImpl.applyConfirmedRepayment`
 *  and `requestRepayment` refuse every other one with a 409, and the same pair is what the
 *  interest-accrual sweep charges interest on. Mirrored here so the actions are hidden rather
 *  than offered and then rejected. */
const REPAYABLE: readonly string[] = ['DISBURSED', 'REPAYING'];

/** A decimal string the server gave, compared with zero -- a test, never arithmetic. */
const isZero = (amount: string | undefined) => Number(amount ?? '0') === 0;

/**
 * The Loans page (2026-11-07, the user: show everything a loan needs, clearly). First how much the policy can borrow
 * and how that is reached -- its value, the product's maximum, what is borrowed and the interest owed, all the
 * server's figures (`GET /policies/{n}/loan-limit`); then each loan with when it was taken, by whom, where it was paid,
 * what it owes split into borrowed and interest, Repay (the one Deposit page, For: Loan) and its history.
 *
 * Origination is offered only while the policy can borrow; the reason is rendered when it cannot.
 */
export function LoansPanel({
  policyNumber,
  canChange,
  canRecordDeposit,
  canRequestPayment,
}: {
  policyNumber: string;
  /** Staff who manage loans and may take one (2026-10-27: an agent reads a loan, and neither takes nor repays one). */
  canChange: boolean;
  /** May record a deposit For: Loan -- the deposit permission, `collections.manage` (money movement D11). */
  canRecordDeposit: boolean;
  /** May ask the payer's phone for a repayment -- `collections.manage` or `policies.service`, as for an invoice. */
  canRequestPayment: boolean;
}) {
  const loadLoans = usePolicyStore((s) => s.loadLoans);
  const loans = usePolicyStore(selectLoans(policyNumber));
  const [originating, setOriginating] = useState(false);
  const [limit, setLimit] = useState<LoanLimit | null>(null);
  const loanRows = loans.data;

  useEffect(() => {
    void loadLoans(policyNumber);
  }, [policyNumber, loadLoans]);

  // Read again whenever the loans are, so what can be borrowed follows a loan taken, refused or repaid.
  useEffect(() => {
    let cancelled = false;
    getLoanLimit(policyNumber)
      .then((l) => {
        if (!cancelled) setLimit(l);
      })
      .catch(() => {
        if (!cancelled) setLimit(null);
      });
    return () => {
      cancelled = true;
    };
  }, [policyNumber, loanRows]);

  if (isInitialLoad(loans)) return <LoadingBlock />;
  if (loans.status === 'error' && loans.error && loans.data === null) {
    return <ErrorPanel error={loans.error} onRetry={() => void loadLoans(policyNumber)} />;
  }
  const rows = loans.data ?? [];
  const mayBorrow = limit !== null && limit.lends && !isZero(limit.canBorrowNow.amount);
  const whyNot = limit === null ? null
    : !limit.lends ? 'This product gives no loans.'
      : isZero(limit.value.amount) ? `No loan available: the cash value is ${limit.value.amount}.`
        : isZero(limit.canBorrowNow.amount) ? 'No loan available: what the loans owe has reached the limit.'
          : null;

  return (
    <div>
      {limit && <LoanSummary limit={limit} />}

      {rows.length === 0 ? (
        <EmptyState
          title="No loans"
          description="No policy loan has been taken against this policy."
        />
      ) : (
        <div className="divide-y divide-border border-t border-border">
          {rows.map((loan) => (
            <LoanCard
              key={loan.loanId ?? JSON.stringify(loan)}
              policyNumber={policyNumber}
              loan={loan}
              canRepay={canRecordDeposit || canRequestPayment}
            />
          ))}
        </div>
      )}

      {canChange && (
      <div className="border-t border-border px-4 py-3">
        {/* Hidden while the form is open: "Take a loan" would otherwise label both this toggle and the form's
            submit button, ambiguous on screen and for any test querying by accessible name. */}
        {!originating && (
          <Button size="sm" variant="outline" disabled={!mayBorrow} onClick={() => setOriginating(true)}>
            Take a loan
          </Button>
        )}
        {/* The reason is rendered, not hidden in a `title`: a tooltip is unreachable by keyboard
            and invisible to a screen reader. */}
        {!originating && whyNot && <p className="mt-1 text-xs text-muted-foreground">{whyNot}</p>}
        {originating && limit && (
          <OriginateLoanForm policyNumber={policyNumber} canBorrowNow={limit.canBorrowNow}
            onDone={() => setOriginating(false)} />
        )}
      </div>
      )}
    </div>
  );
}

/** How much the policy can borrow, and how that is reached: every figure the server's. */
function LoanSummary({ limit }: { limit: LoanLimit }) {
  if (!limit.lends) {
    return <p className="px-4 py-3 text-sm text-muted-foreground">This product gives no loans: its maximum loan is 0%.</p>;
  }
  return (
    <dl className="grid grid-cols-2 gap-x-6 px-4 py-3 sm:grid-cols-3">
      <Field label="Policy value" value={formatMoney(limit.value)} />
      <Field label="Maximum loan"
        value={limit.maxLoanToValuePercent == null
          ? `The whole value, ${formatMoney(limit.limit)}`
          : `${limit.maxLoanToValuePercent}% of the value, ${formatMoney(limit.limit)}`} />
      <Field label="Interest rate" value={`${limit.annualInterestRate}% a year`} />
      <Field label="Borrowed" value={formatMoney(limit.borrowed)} />
      <Field label="Interest owed" value={formatMoney(limit.interestOwed)} />
      <Field label="Can borrow now" value={formatMoney(limit.canBorrowNow)} emphasis />
    </dl>
  );
}

/** What each line of a loan's history is, in plain words; a payout's repayment says which payout. */
function historyWords(line: LoanHistoryLine): string {
  switch (line.kind) {
    case 'DISBURSEMENT':
      return 'Paid out';
    case 'INTEREST_ACCRUAL':
      return 'Interest added';
    case 'REPAYMENT':
      return 'Repaid';
    case 'REVERSAL':
      return 'Reversed';
    case 'SETTLEMENT': {
      const payout = (line.reference ?? '').split(':')[0];
      const from: Record<string, string> = {
        CLAIM: 'the claim', MATURITY: 'the maturity', SURRENDER: 'the surrender', ACCOUNT: 'the account payout',
      };
      return `Repaid from ${from[payout] ?? 'a payout'}`;
    }
    default:
      return line.kind;
  }
}

function LoanHistory({ loanId }: { loanId: string }) {
  const [lines, setLines] = useState<LoanHistoryLine[] | null>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    let cancelled = false;
    getLoanHistory(loanId)
      .then((l) => {
        if (!cancelled) setLines(l);
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });
    return () => {
      cancelled = true;
    };
  }, [loanId]);

  if (failed) return <p className="text-xs text-status-danger-fg">The history could not be read.</p>;
  if (lines === null) return <LoadingBlock />;
  if (lines.length === 0) return <p className="text-xs text-muted-foreground">Nothing has happened on this loan yet.</p>;
  const columns: Column<LoanHistoryLine>[] = [
    { key: 'date', header: 'Date', nowrap: true, render: (l) => formatInstantDay(l.at) },
    { key: 'what', header: 'What', render: (l) => historyWords(l) },
    { key: 'amount', header: 'Amount', align: 'right', render: (l) => formatMoney(l.amount) },
  ];
  return (
    <div className="overflow-hidden rounded-lg border border-border">
      <DataTable caption="Loan history" columns={columns} rows={lines}
        rowKey={(l) => `${l.kind}:${l.at}:${l.amount.amount}`} />
    </div>
  );
}

function LoanCard({
  policyNumber,
  loan,
  canRepay,
}: {
  policyNumber: string;
  loan: LoanView;
  canRepay: boolean;
}) {
  const [showHistory, setShowHistory] = useState(false);
  const loanId = loan.loanId ?? '';
  const repayable = loan.status !== undefined && REPAYABLE.includes(loan.status);
  const depositHref = `/staff/receipts/new?for=LOAN&loan=${encodeURIComponent(loanId)}`
    + `&policy=${encodeURIComponent(policyNumber)}&from=policy`;

  return (
    <div className="space-y-2 px-4 py-3">
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="text-sm font-medium">Loan of {formatMoney(loan.principalAmount)}</p>
          <p className="text-xs text-muted-foreground">
            {loan.takenAt ? `Taken ${formatInstantDay(loan.takenAt)}` : 'Being taken'}
            {loan.takenBy && <> by <StaffName subject={loan.takenBy} /></>}
            {loan.payeeRef && <> · paid to {loan.payeeRef}</>}
          </p>
        </div>
        {loan.status && <StatusBadge kind="loan" value={loan.status} />}
      </div>

      {repayable && (
        <dl className="grid grid-cols-2 gap-x-6 sm:grid-cols-4">
          <Field label="Borrowed, still owed" value={formatMoney(loan.principalOutstanding)} />
          <Field label="Interest owed" value={formatMoney(loan.interestOutstanding)} />
          <Field label="Owed now" value={formatMoney(loan.outstandingBalance)} emphasis />
          {typeof loan.currentInterestRate === 'number' && (
            <Field label="Interest rate" value={`${loan.currentInterestRate}% a year`} />
          )}
        </dl>
      )}

      <div className="flex flex-wrap gap-1.5">
        {/* A repayment is a deposit For: Loan, taken on the one Deposit page (2026-11-07): mobile money asks the phone,
            any other method is recorded for a second person. Neither repays anything until the money is confirmed. */}
        {loanId && repayable && canRepay && (
          <Button asChild size="sm" variant="outline">
            <Link to={depositHref}>Repay</Link>
          </Button>
        )}
        {loanId && (
          <Button size="sm" variant="ghost" aria-expanded={showHistory} onClick={() => setShowHistory((v) => !v)}>
            {showHistory ? 'Hide history' : 'History'}
          </Button>
        )}
      </div>
      {showHistory && loanId && <LoanHistory loanId={loanId} />}

      {loanId && loan.status === 'AWAITING_PAYOUT_APPROVAL' && (
        <PayoutDecision policyNumber={policyNumber} loan={loan} loanId={loanId} />
      )}
      {loan.status === 'PAYOUT_REFUSED' && (
        <p className="text-xs text-muted-foreground">
          Payout refused{loan.payoutDecidedBy && <> by <StaffName subject={loan.payoutDecidedBy} /></>}
          {loan.payoutRefusalReason ? `: ${loan.payoutRefusalReason}` : ''}. Nothing was paid, and the loan value is free again.
        </p>
      )}
    </div>
  );
}

/**
 * A loan taken and waiting for its payout to be released (money movement step 4): who took it and whom it pays, then
 * Release or Refuse for someone holding payouts.approve. The person who took it may release it only with
 * payouts.approve-own -- the server decides, and says so if refused.
 */
function PayoutDecision({ policyNumber, loan, loanId }: { policyNumber: string; loan: LoanView; loanId: string }) {
  const decide = usePolicyStore((s) => s.decideLoanPayout);
  const deciding = usePolicyStore((s) => s.decidingLoanPayout[loanId]);
  const auth = useAuth();
  const viewer = readIdentity(auth.user?.access_token)?.subject;
  const [armed, setArmed] = useState(false);
  const [refusing, setRefusing] = useState(false);
  const [reason, setReason] = useState('');
  const own = barredAsOwn(viewer, loan.takenBy, 'payouts');
  const busy = deciding?.status === 'loading';

  return (
    <div className="mt-1.5 space-y-2">
      <p className="text-xs text-muted-foreground">
        To {loan.payeeRef ?? 'the payee'}
        {loan.takenBy && <> · taken by <StaffName subject={loan.takenBy} /></>} · waiting for a second person to release
        the payout
      </p>
      {can('payouts.approve') && (
        <>
          {deciding?.status === 'error' && deciding.error && <InlineError error={deciding.error} />}
          {own && <p className="text-xs text-muted-foreground">You took this loan, so someone else must release it.</p>}
          {armed ? (
            <ConfirmAct
              heading="Release this loan's payout?"
              consequence={
                <>
                  <strong>{formatMoney(loan.principalAmount)}</strong> is paid out to {loan.payeeRef ?? 'the payee'}.
                </>
              }
              reversal="If the payment fails, the loan is reversed and its value freed again."
              confirmLabel={`Pay ${formatMoney(loan.principalAmount)}`}
              tone="danger"
              busy={busy}
              onConfirm={() => {
                void decide(policyNumber, loanId, null);
                setArmed(false);
              }}
              onCancel={() => setArmed(false)}
            />
          ) : refusing ? (
            <div className="space-y-2">
              <FormField required label="Why is it refused?">
                <Input inputSize="sm" value={reason} onChange={(e) => setReason(e.target.value)} />
              </FormField>
              <div className="flex gap-2">
                <Button size="sm" variant="danger" disabled={reason.trim() === '' || busy}
                  onClick={() => void decide(policyNumber, loanId, reason.trim())}>
                  Refuse payout
                </Button>
                <Button size="sm" variant="ghost" onClick={() => setRefusing(false)}>
                  Cancel
                </Button>
              </div>
              <p className="text-xs text-muted-foreground">Nothing has been paid, so nothing is reversed.</p>
            </div>
          ) : (
            <div className="flex gap-2">
              <Button size="sm" variant="primary" disabled={own} onClick={() => setArmed(true)}>
                Release payout
              </Button>
              <Button size="sm" variant="outline" disabled={own} onClick={() => setRefusing(true)}>
                Refuse
              </Button>
            </div>
          )}
        </>
      )}
    </div>
  );
}

/** Take a loan: the most that may be borrowed is said before anything is typed (2026-11-07). */
function OriginateLoanForm({
  policyNumber,
  canBorrowNow,
  onDone,
}: {
  policyNumber: string;
  canBorrowNow: LoanLimit['canBorrowNow'];
  onDone: () => void;
}) {
  const originateLoan = usePolicyStore((s) => s.originateLoan);
  const resetOriginateLoan = usePolicyStore((s) => s.resetOriginateLoan);
  const originating = usePolicyStore(selectOriginatingLoan(policyNumber));

  useEffect(() => {
    resetOriginateLoan(policyNumber);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [policyNumber]);

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<OriginateLoanFormValues>({
    ...VALIDATE_ON_TOUCH,
    resolver: zodResolver(originateLoanFormSchema),
    defaultValues: blankOriginateLoanForm(),
  });

  async function onSubmit(values: OriginateLoanFormValues) {
    await originateLoan(policyNumber, toOriginateApiRequest(values));
    if (usePolicyStore.getState().originatingLoan[policyNumber]?.status === 'success') onDone();
  }

  return (
    <form
      className="space-y-2 rounded-md border border-border p-2.5"
      onSubmit={(e) => void handleSubmit(onSubmit)(e)}
    >
      <FormField required label="Loan amount (TZS)" hint={`Up to ${formatMoney(canBorrowNow)}.`}
        error={errors.amount?.message}>
        <Input inputSize="sm" inputMode="decimal" {...register('amount')} />
      </FormField>

      <FormField required label="Pay the loan to" hint="The mobile-money number the loan is paid to."
        error={errors.payeeRef?.message}>
        <Input inputSize="sm" inputMode="tel" {...register('payeeRef')} />
      </FormField>

      {/* Shown verbatim rather than reworded: a refusal names the most the policy can borrow, or says it is not in
          force. Both are more useful than anything this form could say on its own. */}
      {originating.status === 'error' && originating.error && (
        <InlineError error={originating.error} />
      )}

      <p className="text-xs text-muted-foreground">A second person releases the payout.</p>

      <div className="flex items-center gap-1.5">
        <Button type="submit" size="sm" variant="primary" pending={originating.status === 'loading'}>
          Take a loan
        </Button>
        <Button type="button" size="sm" variant="ghost" onClick={onDone}>
          Cancel
        </Button>
      </div>
    </form>
  );
}
