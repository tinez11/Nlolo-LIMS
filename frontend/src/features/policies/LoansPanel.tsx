import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import type { LoanView, Money } from '@/api/types';
import { StatusBadge } from '@/components/StatusBadge';
import { EmptyState, ErrorPanel, LoadingBlock } from '@/components/states';
import { Button } from '@/components/ui/button';
import { formatMoney } from '@/lib/money';
import { isInitialLoad } from '@/store/createResourceSlice';
import {
  selectLoans,
  selectOriginatingLoan,
  selectRepayingLoan,
  usePolicyStore,
} from '@/store/policyStore';
import {
  blankLoanRepaymentForm,
  loanRepaymentFormSchema,
  toApiRequest as toRepaymentApiRequest,
  type LoanRepaymentFormValues,
} from './loanRepaymentForm';
import {
  blankOriginateLoanForm,
  originateLoanFormSchema,
  toApiRequest as toOriginateApiRequest,
  type OriginateLoanFormValues,
} from './originateLoanForm';
import { FormField } from '@/components/FormField';
import { Input } from '@/components/ui/input';

/** Only these two statuses accept a repayment -- `PolicyLoanApiImpl.recordRepayment`
 *  throws `LoanNotEligibleException` (409) for every other one, and the same pair is
 *  what the interest-accrual sweep charges interest on. Mirrored here so the action is
 *  hidden rather than offered and then rejected. */
const REPAYABLE: readonly string[] = ['DISBURSED', 'REPAYING'];

/**
 * The loans panel: reads the policy's loans and, unlike the read-only table this
 * replaced, lets staff act on them -- `POST /policies/{n}/loans` to originate and
 * `POST /loans/{loanId}/repayments` to record a repayment.
 *
 * Both endpoints existed and were reachable from the very first release of this module;
 * nothing in this console called either of them, so a policy loan could be listed but
 * never created or repaid from the UI.
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
}: {
  policyNumber: string;
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
            />
          ))}
        </div>
      )}

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
            {...(hasCashValue
              ? {}
              : {
                  title:
                    'This policy has no cash value to borrow against, so a loan would be refused. The platform does not credit cash value yet.',
                })}
            onClick={() => setOriginating(true)}
          >
            Take a loan
          </Button>
        )}
        {!originating && !hasCashValue && (
          <p className="mt-1 text-[11px] text-muted-foreground">
            A loan is limited to the policy&rsquo;s cash value, which is still 0.00 —
            nothing credits it yet.
          </p>
        )}
        {originating && (
          <OriginateLoanForm policyNumber={policyNumber} onDone={() => setOriginating(false)} />
        )}
      </div>
    </div>
  );
}

function LoanRow({ policyNumber, loan }: { policyNumber: string; loan: LoanView }) {
  const [repaying, setRepaying] = useState(false);
  const loanId = loan.loanId ?? '';
  const repayable = loan.status !== undefined && REPAYABLE.includes(loan.status);

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
          <p className="text-[11px] text-muted-foreground">
            of {formatMoney(loan.principalAmount)} principal
          </p>
        </div>
      </div>

      {typeof loan.currentInterestRate === 'number' && (
        <p className="mt-0.5 text-[11px] text-muted-foreground">
          {loan.currentInterestRate}% a year, accrued daily
        </p>
      )}

      {loanId && repayable && !repaying && (
        <div className="mt-1.5">
          <Button size="sm" variant="ghost" className="-ml-2" onClick={() => setRepaying(true)}>
            Record repayment
          </Button>
        </div>
      )}

      {repaying && loanId && (
        <RepaymentForm
          policyNumber={policyNumber}
          loanId={loanId}
          onDone={() => setRepaying(false)}
        />
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
        <p role="alert" className="text-[11px] text-status-danger-fg">
          {originating.error.detail ?? originating.error.title}
        </p>
      )}

      <p className="text-[11px] text-muted-foreground">
        Limited to the policy&rsquo;s available cash value. Disbursement is requested
        automatically and confirmed by the payment rail, so the loan stays pending until
        the money is sent.
      </p>

      <div className="flex items-center gap-1.5">
        <Button type="submit" size="sm" disabled={originating.status === 'loading'}>
          {originating.status === 'loading' ? 'Requesting…' : 'Take a loan'}
        </Button>
        <Button type="button" size="sm" variant="ghost" onClick={onDone}>
          Cancel
        </Button>
      </div>
    </form>
  );
}

function RepaymentForm({
  policyNumber,
  loanId,
  onDone,
}: {
  policyNumber: string;
  loanId: string;
  onDone: () => void;
}) {
  const recordLoanRepayment = usePolicyStore((s) => s.recordLoanRepayment);
  const resetRecordLoanRepayment = usePolicyStore((s) => s.resetRecordLoanRepayment);
  const repaying = usePolicyStore(selectRepayingLoan(loanId));

  useEffect(() => {
    resetRecordLoanRepayment(loanId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loanId]);

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<LoanRepaymentFormValues>({
    resolver: zodResolver(loanRepaymentFormSchema),
    defaultValues: blankLoanRepaymentForm(),
  });

  async function onSubmit(values: LoanRepaymentFormValues) {
    await recordLoanRepayment(policyNumber, loanId, toRepaymentApiRequest(values));
    if (usePolicyStore.getState().repayingLoan[loanId]?.status === 'success') onDone();
  }

  return (
    <form
      className="mt-2 space-y-2 rounded-md border border-border p-2.5"
      onSubmit={(e) => void handleSubmit(onSubmit)(e)}
    >
      <FormField label="Repayment amount (TZS)" error={errors.amount?.message}>
        <Input inputSize="sm" placeholder="200000" inputMode="decimal" {...register('amount')} />
      </FormField>

      <FormField label="Payment reference" error={errors.paymentReference?.message}>
        <Input
          inputSize="sm"
          placeholder="Receipt or transaction id"
          {...register('paymentReference')}
        />
      </FormField>

      {repaying.status === 'error' && repaying.error && (
        <p role="alert" className="text-[11px] text-status-danger-fg">
          {repaying.error.detail ?? repaying.error.title}
        </p>
      )}

      <p className="text-[11px] text-muted-foreground">
        Partial repayments are accepted as often as needed. Clearing the balance settles
        the loan.
      </p>

      <div className="flex items-center gap-1.5">
        <Button type="submit" size="sm" disabled={repaying.status === 'loading'}>
          {repaying.status === 'loading' ? 'Recording…' : 'Record repayment'}
        </Button>
        <Button type="button" size="sm" variant="ghost" onClick={onDone}>
          Cancel
        </Button>
      </div>
    </form>
  );
}
