import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import type { TermAccountView } from '@/api/types';
import { Field } from '@/components/Field';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { formatDate } from '@/lib/dates';
import { startMutation } from '@/lib/idempotency';
import { useAccumulationStore } from '@/store/accumulationStore';
import { payeeSchema, type PayeeValues } from './depositForms';
import { VALIDATE_ON_TOUCH } from '@/lib/formTiming';

const STATUS_LABELS: Record<TermAccountView['status'], string> = {
  RUNNING: 'Running',
  MATURED: 'Matured and paid out',
  AWAITING_PAYEE: 'Matured, waiting for a payee',
};

/**
 * A term account (2026-10-21): contributions build a balance as on any savings account, at the rate the policy locked
 * when it was sold, and nothing is paid out until it matures -- then the whole balance goes to the number contributions
 * came from. No maturity choice, unlike a fixed-term deposit's: the payout is automatic.
 */
export function TermAccountSection({ termAccount, isFinance }: { termAccount: TermAccountView; isFinance: boolean }) {
  return (
    <section className="space-y-3" aria-label="Term account">
      <dl className="grid grid-cols-1 gap-x-8 gap-y-1 rounded-md border border-border px-4 py-2 sm:grid-cols-2">
        <Field label="Locked rate" value={`${termAccount.ratePercent}% a year`} />
        <Field label="Matures" value={formatDate(termAccount.maturityDate)} />
        <Field label="Status" value={STATUS_LABELS[termAccount.status]} />
        <Field label="Pays out to" value={termAccount.defaultPayeeRef ?? 'Not known yet'} />
      </dl>
      {termAccount.status === 'RUNNING' && (
        <p className="text-xs text-muted-foreground">
          Nothing can be withdrawn or surrendered until it matures on {formatDate(termAccount.maturityDate)}. Then the
          whole balance is paid out automatically.
        </p>
      )}
      {termAccount.status === 'AWAITING_PAYEE' &&
        (isFinance ? (
          <PayeePanel policyNumber={termAccount.policyNumber} />
        ) : (
          <p className="text-sm">This account has matured and waits for finance to record who it is paid to.</p>
        ))}
    </section>
  );
}

function PayeePanel({ policyNumber }: { policyNumber: string }) {
  const payOut = useAccumulationStore((s) => s.payOutTermAccount);
  const acting = useAccumulationStore((s) => s.acting[`termAccountPayout.${policyNumber}`]);
  const form = useForm<PayeeValues>({ ...VALIDATE_ON_TOUCH, resolver: zodResolver(payeeSchema), defaultValues: { payeeRef: '' } });

  return (
    <form
      className="space-y-3 rounded-md border border-border p-3"
      aria-label="Record payee and pay"
      onSubmit={form.handleSubmit((values) => void payOut(policyNumber, values.payeeRef, startMutation()))}
    >
      <p className="text-sm">This account has matured and there is no number to pay it to.</p>
      <FormField label="Pay to" error={form.formState.errors.payeeRef?.message}>
        <Input inputSize="sm" {...form.register('payeeRef')} />
      </FormField>
      {acting?.status === 'error' && acting.error && <InlineError error={acting.error} />}
      {acting?.status === 'success' && (
        <p className="text-xs text-muted-foreground" role="status">
          The payment has been requested from the provider.
        </p>
      )}
      <Button type="submit" size="sm" disabled={acting?.status === 'loading'}>
        Record payee and pay
      </Button>
    </form>
  );
}
