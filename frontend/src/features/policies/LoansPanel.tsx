import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useAuth } from 'react-oidc-context';
import { Link } from 'react-router-dom';
import type { LoanView, Money } from '@/api/types';
import { can } from '@/auth/access';
import { readIdentity } from '@/auth/claims';
import { ConfirmAct } from '@/components/ConfirmAct';
import { StaffName } from '@/components/StaffName';
import { StatusBadge } from '@/components/StatusBadge';
import { barredAsOwn } from '@/lib/ownWork';
import { InlineError } from '@/components/InlineError';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { startMutation, type MutationAttempt } from '@/lib/idempotency';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import {
  selectLoans,
  selectOriginatingLoan,
  selectRequestingLoanPayment,
  usePolicyStore,
} from '@/store/policyStore';
import {
  blankLoanPaymentForm,
  loanPaymentFormSchema,
  toLoanPaymentRequest,
  type LoanPaymentFormValues,
} from './loanPaymentForm';
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

/**
 * The loans panel: reads the policy's loans and lets staff act on them --
 * `POST /policies/{n}/loans` to originate, and for a repayment (money movement step 1,
 * 2026-11-04) either a deposit For: Loan on the receipt page or
 * `POST /loans/{loanId}/payment-request` to ask the payer's phone. Neither repays anything
 * until the money is confirmed; a repayment can no longer be booked by typing it in.
 *
 * The NO-PAGER shape is inherited: `GET /policies/{n}/loans` returns a bare unpaged
 * array, so the whole set is on screen and there is nothing to page through.
 *
 * **Origination is gated on cash value, and today that gate is always shut.** A loan is
 * capped at the policy's available loan value, which is cash value net of encumbrance
 * and live reservations -- and nothing on this platform ever CREDITS cash value:
 * `PolicyApiImpl.issuePolicy` opens every `PolicyAccount` at `BigDecimal.ZERO` and no
 * code path or migration writes it again. So origination against real data always comes
 * back 409 "exceeds available loan value 0". Crediting cash value is an actuarial
 * accumulation model, not an oversight this panel can route around.
 *
 * The action is therefore rendered DISABLED with the reason on it, rather than as a live
 * button that can only ever produce an error -- the same discipline `PolicyDetailPage`
 * already applies to the deferred Surrender action. The check is on the live value, not
 * hardcoded, so the moment cash value is credited the button works with no change here.
 */
export function LoansPanel({
  policyNumber,
  cashValue,
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
  // `| undefined` explicitly, not just optional: this project runs
  // exactOptionalPropertyTypes, so an optional prop does not accept an
  // explicitly-undefined value -- and the caller passes `policy?.cashValue`.
  cashValue?: Money | undefined;
}) {
  const loadLoans = usePolicyStore((s) => s.loadLoans);
  const loans = usePolicyStore(selectLoans(policyNumber));
  const [originating, setOriginating] = useState(false);
  // Number() is safe for a strictly-greater-than-zero test on a decimal string, and only
  // for that -- the amount itself is never parsed to a number anywhere on the way to the
  // wire (see originateLoanForm's own note on why).
  const hasCashValue = Number(cashValue?.amount ?? '0') > 0;

  useEffect(() => {
    void loadLoans(policyNumber);
  }, [policyNumber, loadLoans]);

  if (isInitialLoad(loans)) return <LoadingBlock />;
  if (loans.status === 'error' && loans.error && loans.data === null) {
    return <ErrorPanel error={loans.error} onRetry={() => void loadLoans(policyNumber)} />;
  }
  const rows = loans.data ?? [];

  return (
    <div>
      {rows.length === 0 ? (
        <EmptyState
          title="No loans"
          description="No policy loan has been taken against this policy."
        />
      ) : (
        <div className="divide-y divide-border">
          {rows.map((loan) => (
            <LoanRow
              key={loan.loanId ?? JSON.stringify(loan)}
              policyNumber={policyNumber}
              loan={loan}
              canRecordDeposit={canRecordDeposit}
              canRequestPayment={canRequestPayment}
            />
          ))}
        </div>
      )}

      {canChange && (
      <div className="border-t border-border px-4 py-3">
        {/* Hidden while the form is open, for the same reason the invoice actions are:
            "Take a loan" would otherwise label both this toggle and the open form's
            submit button, which is ambiguous on screen and for any test querying by
            accessible name. */}
        {!originating && (
          <Button
            size="sm"
            variant="ghost"
            className="-ml-2"
            disabled={!hasCashValue}
            onClick={() => setOriginating(true)}
          >
            Take a loan
          </Button>
        )}
        {/* The reason is rendered, not hidden in a `title`: a tooltip is unreachable by keyboard
            and invisible to a screen reader, so the disabled button said nothing to anyone who
            could not hover it. */}
        {!originating && !hasCashValue && (
          <p className="mt-1 text-xs text-muted-foreground">
            A loan is limited to the policy&rsquo;s cash value, which is 0.00. A savings policy
            builds value from its first two or three full years; a term policy never does.
          </p>
        )}
        {originating && (
          <OriginateLoanForm policyNumber={policyNumber} onDone={() => setOriginating(false)} />
        )}
      </div>
      )}
    </div>
  );
}

function LoanRow({
  policyNumber,
  loan,
  canRecordDeposit,
  canRequestPayment,
}: {
  policyNumber: string;
  loan: LoanView;
  canRecordDeposit: boolean;
  canRequestPayment: boolean;
}) {
  const [asking, setAsking] = useState(false);
  const loanId = loan.loanId ?? '';
  const repayable = loan.status !== undefined && REPAYABLE.includes(loan.status);
  const depositHref = `/staff/receipts/new?for=LOAN&loan=${encodeURIComponent(loanId)}`
    + `&policy=${encodeURIComponent(policyNumber)}&from=policy`;

  return (
    <div className="px-4 py-3">
      <div className="flex items-center justify-between gap-2">
        <div className="min-w-0">
          <span className="font-mono text-xs">{loanId ? loanId.slice(0, 8) : '—'}</span>
          <span className="ml-2">
            {loan.status && <StatusBadge kind="loan" value={loan.status} />}
          </span>
        </div>
        <div className="shrink-0 text-right">
          <span className="text-sm font-medium">{formatMoney(loan.outstandingBalance)}</span>
          <p className="text-xs text-muted-foreground">
            of {formatMoney(loan.principalAmount)} principal
          </p>
        </div>
      </div>

      {typeof loan.currentInterestRate === 'number' && (
        <p className="mt-0.5 text-xs text-muted-foreground">
          {loan.currentInterestRate}% a year, accrued daily
        </p>
      )}

      {/* A repayment is money in like a premium (money movement step 1): recorded on the receipt page and confirmed
          by a second person, or asked of the payer's phone. Neither repays anything until the money is confirmed. */}
      {loanId && repayable && !asking && (canRecordDeposit || canRequestPayment) && (
        <div className="mt-1.5 flex flex-wrap gap-1.5">
          {canRecordDeposit && (
            <Button asChild size="sm" variant="ghost" className="-ml-2">
              <Link to={depositHref}>Record deposit</Link>
            </Button>
          )}
          {canRequestPayment && (
            <Button size="sm" variant="ghost" className={canRecordDeposit ? undefined : '-ml-2'} onClick={() => setAsking(true)}>
              Request mobile-money payment
            </Button>
          )}
        </div>
      )}

      {asking && loanId && (
        <LoanPaymentForm policyNumber={policyNumber} loan={loan} onDone={() => setAsking(false)} />
      )}

      {loanId && loan.status === 'AWAITING_PAYOUT_APPROVAL' && (
        <PayoutDecision policyNumber={policyNumber} loan={loan} loanId={loanId} />
      )}
      {loan.status === 'PAYOUT_REFUSED' && (
        <p className="mt-0.5 text-xs text-muted-foreground">
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
              <FormField label="Why is it refused?">
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

function OriginateLoanForm({
  policyNumber,
  onDone,
}: {
  policyNumber: string;
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
      <FormField label="Loan amount (TZS)" error={errors.amount?.message}>
        <Input inputSize="sm" placeholder="500000" inputMode="decimal" {...register('amount')} />
      </FormField>

      <FormField label="Payee reference" error={errors.payeeRef?.message}>
        <Input inputSize="sm" placeholder="Mobile-money destination" {...register('payeeRef')} />
      </FormField>

      {/* Shown verbatim rather than reworded: a 409 here names the policy's actual
          available loan value, or says the policy is not in force. Both are more
          useful than anything this form could say on its own. */}
      {originating.status === 'error' && originating.error && (
        <InlineError error={originating.error} />
      )}

      <p className="text-xs text-muted-foreground">
        Limited to the policy&rsquo;s available cash value. A second person releases the payout
        before anything is sent, and the payment rail confirms it, so the loan stays pending
        until the money is sent.
      </p>

      <div className="flex items-center gap-1.5">
        <Button type="submit" size="sm" pending={originating.status === 'loading'}>
          Take a loan
        </Button>
        <Button type="button" size="sm" variant="ghost" onClick={onDone}>
          Cancel
        </Button>
      </div>
    </form>
  );
}

/** Ask the payer's phone for a repayment, by default what the loan owes as the server stated it. The same shape as the
 *  invoice row's mobile-money request (InvoicesPanel.MobileMoneyRequestForm). */
function LoanPaymentForm({
  policyNumber,
  loan,
  onDone,
}: {
  policyNumber: string;
  loan: LoanView;
  onDone: () => void;
}) {
  const loanId = loan.loanId ?? '';
  const currency = loan.outstandingBalance?.currencyCode ?? 'TZS';
  const requestLoanPayment = usePolicyStore((s) => s.requestLoanPayment);
  const resetRequestLoanPayment = usePolicyStore((s) => s.resetRequestLoanPayment);
  const requesting = usePolicyStore(selectRequestingLoanPayment(loanId));
  // Minted once per mount, reused across retries; reopening the form starts a new attempt.
  const [attempt] = useState<MutationAttempt>(() => startMutation());

  useEffect(() => {
    resetRequestLoanPayment(loanId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loanId]);

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<LoanPaymentFormValues>({
    ...VALIDATE_ON_TOUCH,
    resolver: zodResolver(loanPaymentFormSchema),
    defaultValues: blankLoanPaymentForm(loan.outstandingBalance?.amount ?? ''),
  });

  async function onSubmit(values: LoanPaymentFormValues) {
    await requestLoanPayment(policyNumber, loanId, toLoanPaymentRequest(values, currency), attempt);
    if (usePolicyStore.getState().requestingLoanPayment[loanId]?.status === 'success') onDone();
  }

  return (
    <form
      className="mt-2 space-y-2 rounded-md border border-border p-2.5"
      onSubmit={(e) => void handleSubmit(onSubmit)(e)}
    >
      <FormField label="Payer reference" error={errors.payerRef?.message}>
        <Input inputSize="sm" placeholder="The mobile-money number to ask" {...register('payerRef')} />
      </FormField>

      <FormField label={`Amount (${currency})`} error={errors.amount?.message}>
        <Input inputSize="sm" inputMode="decimal" {...register('amount')} />
      </FormField>

      {requesting.status === 'error' && requesting.error && (
        <InlineError error={requesting.error} />
      )}

      <p className="text-xs text-muted-foreground">
        Nothing is repaid until the money is confirmed. At most what the loan owes now.
      </p>

      <div className="flex items-center gap-1.5">
        <Button type="submit" size="sm" pending={requesting.status === 'loading'}>
          Request payment
        </Button>
        <Button type="button" size="sm" variant="ghost" onClick={onDone}>
          Cancel
        </Button>
      </div>
    </form>
  );
}
